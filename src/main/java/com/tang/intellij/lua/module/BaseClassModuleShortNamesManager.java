/*
 * Copyright (c) 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.module;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Processor;
import com.tang.intellij.lua.comment.psi.api.LuaComment;
import com.tang.intellij.lua.ext.ILuaTypeInfer;
import com.tang.intellij.lua.lang.LuaFileType;
import com.tang.intellij.lua.psi.LuaCallExpr;
import com.tang.intellij.lua.psi.LuaClassField;
import com.tang.intellij.lua.psi.LuaClassMember;
import com.tang.intellij.lua.psi.LuaExpr;
import com.tang.intellij.lua.psi.LuaLiteralExpr;
import com.tang.intellij.lua.psi.LuaLocalDef;
import com.tang.intellij.lua.psi.LuaNameDef;
import com.tang.intellij.lua.psi.LuaTypeGuessable;
import com.tang.intellij.lua.psi.PsiExtensionKt;
import com.tang.intellij.lua.psi.Visibility;
import com.tang.intellij.lua.psi.search.LuaShortNamesManager;
import com.tang.intellij.lua.search.SearchContext;
import com.tang.intellij.lua.ty.ITy;
import com.tang.intellij.lua.ty.ITyClass;
import com.tang.intellij.lua.ty.Ty;
import com.tang.intellij.lua.ty.TyClass;
import com.tang.intellij.lua.ty.TyFlags;
import com.tang.intellij.lua.ty.TyLazyClass;
import com.tang.intellij.lua.ty.TySerializedClass;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds synthetic fields to the EmmyLua class named {@code Module}.
 *
 * <p>Every direct call of the form {@code BaseClass("moduleName")} in a project Lua file contributes one field:
 * {@code Module.moduleName}. The field type is inferred from the local variable receiving the BaseClass call; when
 * a class annotation is attached to that local declaration, that class is used directly. Without an annotation,
 * a deterministic synthetic subtype of BaseClass is used, so each module keeps an independent member set.</p>
 */
public final class BaseClassModuleShortNamesManager extends LuaShortNamesManager implements ILuaTypeInfer {
    private static final String MODULE_CLASS_NAME = "Module";
    private static final String FACTORY_NAME = "BaseClass";
    private static final String SYNTHETIC_TYPE_PREFIX = "$BaseClassModule$";
    private static final Key<Cache> CACHE_KEY = Key.create("emmylua.baseclass.module.members");
    private static final ITyClass MODULE_TYPE = new TyLazyClass(MODULE_CLASS_NAME);

    /**
     * Gives every literal BaseClass("name") call a deterministic, independent type.
     *
     * <p>This hook intentionally runs before EmmyLua's normal type inference. Existing LuaNameDef inference then
     * adds its own local anonymous type as usual, while class members are indexed under this synthetic type instead
     * of the shared BaseClass type. As a result, methods from different registered modules do not leak into one
     * another, and the synthetic type can still inherit the real BaseClass API.</p>
     */
    @Override
    public ITy inferType(LuaTypeGuessable target, SearchContext context) {
        if (!(target instanceof LuaCallExpr call) || !isBaseClassCall(call)) {
            return Ty.Companion.getUNKNOWN();
        }

        String moduleName = extractString(call.getFirstStringArg());
        if (moduleName == null || moduleName.isEmpty()) {
            return Ty.Companion.getUNKNOWN();
        }
        return syntheticType(moduleName);
    }

    @Override
    public boolean processMembers(ITyClass type,
                                  SearchContext context,
                                  Processor<LuaClassMember> processor) {
        if (!isModuleType(type) || !canSearch(context)) {
            return true;
        }
        for (Registration registration : registrations(context)) {
            if (!processor.process(registration.field())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean processMembers(String className,
                                  String fieldName,
                                  SearchContext context,
                                  Processor<LuaClassMember> processor,
                                  boolean visitSuper) {
        if (!MODULE_CLASS_NAME.equals(className) || !canSearch(context)) {
            return true;
        }
        for (Registration registration : registrations(context)) {
            if (registration.name.equals(fieldName) && !processor.process(registration.field())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Collection<LuaClassMember> getClassMembers(String clazzName, SearchContext context) {
        if (!MODULE_CLASS_NAME.equals(clazzName) || !canSearch(context)) {
            return Collections.emptyList();
        }
        List<LuaClassMember> result = new ArrayList<>();
        for (Registration registration : registrations(context)) {
            result.add(registration.field());
        }
        return result;
    }

    private static boolean isModuleType(ITyClass type) {
        return type != null && MODULE_CLASS_NAME.equals(type.getClassName());
    }

    private static boolean canSearch(SearchContext context) {
        return context != null && !context.getForStub() && !context.isDumb();
    }

    private static List<Registration> registrations(SearchContext context) {
        Project project = context.getProject();
        Cache cache = project.getUserData(CACHE_KEY);
        if (cache == null) {
            synchronized (CACHE_KEY) {
                cache = project.getUserData(CACHE_KEY);
                if (cache == null) {
                    cache = new Cache();
                    project.putUserData(CACHE_KEY, cache);
                }
            }
        }

        List<Registration> registrations = cache.refresh(project);
        GlobalSearchScope requestedScope = context.getScope();
        if (requestedScope == null) {
            return registrations;
        }
        List<Registration> scoped = new ArrayList<>(registrations.size());
        for (Registration registration : registrations) {
            VirtualFile file = registration.virtualFile();
            if (file == null || requestedScope.contains(file)) {
                scoped.add(registration);
            }
        }
        return scoped;
    }

    private static List<Registration> scanFile(PsiFile file) {
        Map<String, Registration> byName = new LinkedHashMap<>();
        scanFile(file, byName);
        return new ArrayList<>(byName.values());
    }

    private static void scanFile(PsiFile file, Map<String, Registration> byName) {
        Deque<PsiElement> stack = new ArrayDeque<>();
        stack.push(file);
        while (!stack.isEmpty()) {
            PsiElement element = stack.pop();
            if (element instanceof LuaCallExpr call && isBaseClassCall(call)) {
                PsiElement firstStringArg = call.getFirstStringArg();
                String moduleName = extractString(firstStringArg);
                if (moduleName != null && !moduleName.isEmpty()) {
                    LuaLocalDef localDef = findLocalDef(call);
                    LuaNameDef assignedName = findAssignedName(call, localDef);
                    Registration registration = new Registration(moduleName, call, firstStringArg, localDef, assignedName);
                    // Runtime registration is name based. Keep one deterministic field per name.
                    byName.putIfAbsent(moduleName, registration);
                }
            }

            for (PsiElement child = element.getLastChild(); child != null; child = child.getPrevSibling()) {
                stack.push(child);
            }
        }
    }

    private static boolean isBaseClassCall(LuaCallExpr call) {
        if (!call.isFunctionCall()) {
            return false;
        }
        LuaExpr callee = call.getExpr();
        return callee != null && FACTORY_NAME.equals(callee.getText());
    }

    private static LuaLocalDef findLocalDef(LuaCallExpr call) {
        PsiElement element = call;
        while (element != null && !(element instanceof LuaLocalDef)) {
            // Do not accidentally bind a call nested inside a function body to an outer local declaration.
            if (element != call && element instanceof PsiFile) {
                return null;
            }
            element = element.getParent();
        }
        return (LuaLocalDef) element;
    }

    private static LuaNameDef findAssignedName(LuaCallExpr call, LuaLocalDef localDef) {
        if (localDef == null || localDef.getExprList() == null || localDef.getNameList() == null) {
            return null;
        }

        List<LuaExpr> expressions = localDef.getExprList().getExprList();
        int expressionIndex = -1;
        PsiElement cursor = call;
        while (cursor != null && cursor.getParent() != localDef.getExprList()) {
            cursor = cursor.getParent();
        }
        if (cursor instanceof LuaExpr) {
            expressionIndex = expressions.indexOf(cursor);
        }
        if (expressionIndex < 0) {
            expressionIndex = expressions.indexOf(call);
        }

        List<LuaNameDef> names = localDef.getNameList().getNameDefList();
        if (names.isEmpty()) {
            return null;
        }
        if (expressionIndex < 0) {
            expressionIndex = 0;
        }
        return names.get(Math.min(expressionIndex, names.size() - 1));
    }

    private static ITyClass syntheticType(String moduleName) {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(moduleName.getBytes(StandardCharsets.UTF_8));
        return new TySerializedClass(
                SYNTHETIC_TYPE_PREFIX + encoded,
                moduleName,
                FACTORY_NAME,
                null,
                TyFlags.ANONYMOUS
        );
    }

    private static String extractString(PsiElement stringElement) {
        if (stringElement == null) {
            return null;
        }
        if (stringElement instanceof LuaLiteralExpr literal) {
            return PsiExtensionKt.getStringValue(literal);
        }
        String text = stringElement.getText();
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return unescapeShortString(text.substring(1, text.length() - 1));
            }
        }
        if (text.startsWith("[")) {
            int equals = 0;
            int index = 1;
            while (index < text.length() && text.charAt(index) == '=') {
                equals++;
                index++;
            }
            if (index < text.length() && text.charAt(index) == ']') {
                String close = "]" + "=".repeat(equals) + "]";
                if (text.endsWith(close)) {
                    return text.substring(index + 1, text.length() - close.length());
                }
            }
        }
        // getFirstStringArg may return the token content rather than the whole literal on some parser branches.
        return text;
    }

    private static String unescapeShortString(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                result.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'a' -> result.append('\u0007');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'v' -> result.append('\u000B');
                case '\\' -> result.append('\\');
                case '"' -> result.append('"');
                case '\'' -> result.append('\'');
                case '\n' -> { /* escaped physical newline */ }
                case '\r' -> {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
                default -> result.append(next);
            }
        }
        return result.toString();
    }

    private static final class Cache {
        private final Map<VirtualFile, FileEntry> files = new LinkedHashMap<>();
        private List<Registration> flattened = Collections.emptyList();

        private synchronized List<Registration> refresh(Project project) {
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            PsiManager psiManager = PsiManager.getInstance(project);
            Map<VirtualFile, FileEntry> current = new LinkedHashMap<>();
            boolean[] changed = {false};

            FileTypeIndex.processFiles(LuaFileType.INSTANCE, file -> {
                PsiFile psiFile = psiManager.findFile(file);
                if (psiFile == null) {
                    return true;
                }
                long stamp = psiFile.getModificationStamp();
                FileEntry old = files.get(file);
                if (old != null && old.modificationStamp == stamp && old.isValid()) {
                    current.put(file, old);
                } else {
                    current.put(file, new FileEntry(stamp, scanFile(psiFile)));
                    changed[0] = true;
                }
                return true;
            }, scope);

            if (current.size() != files.size() || !current.keySet().equals(files.keySet())) {
                changed[0] = true;
            }

            if (changed[0]) {
                files.clear();
                files.putAll(current);
                List<Map.Entry<VirtualFile, FileEntry>> orderedFiles = new ArrayList<>(files.entrySet());
                orderedFiles.sort(Comparator.comparing(entry -> safePath(entry.getKey())));

                Map<String, Registration> byName = new LinkedHashMap<>();
                for (Map.Entry<VirtualFile, FileEntry> entry : orderedFiles) {
                    List<Registration> orderedRegistrations = new ArrayList<>(entry.getValue().registrations);
                    orderedRegistrations.sort(Comparator.comparingInt(Registration::textOffset));
                    for (Registration registration : orderedRegistrations) {
                        byName.putIfAbsent(registration.name, registration);
                    }
                }
                List<Registration> result = new ArrayList<>(byName.values());
                result.sort(Comparator.comparing(registration -> registration.name));
                flattened = Collections.unmodifiableList(result);
            }
            return flattened;
        }

        private static String safePath(VirtualFile file) {
            String path = file.getPath();
            return path == null ? "" : path;
        }
    }

    private static final class FileEntry {
        private final long modificationStamp;
        private final List<Registration> registrations;

        private FileEntry(long modificationStamp, List<Registration> registrations) {
            this.modificationStamp = modificationStamp;
            this.registrations = registrations;
        }

        private boolean isValid() {
            for (Registration registration : registrations) {
                if (!registration.call.isValid()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Registration implements InvocationHandler {
        private final String name;
        private final LuaCallExpr call;
        private final PsiElement nameElement;
        private final LuaLocalDef localDef;
        private final LuaNameDef assignedName;
        private volatile LuaClassField field;

        private Registration(String name,
                             LuaCallExpr call,
                             PsiElement nameElement,
                             LuaLocalDef localDef,
                             LuaNameDef assignedName) {
            this.name = name;
            this.call = call;
            this.nameElement = nameElement != null ? nameElement : call;
            this.localDef = localDef;
            this.assignedName = assignedName;
        }

        private int textOffset() {
            return call.getTextOffset();
        }

        private LuaClassField field() {
            LuaClassField current = field;
            if (current == null) {
                synchronized (this) {
                    current = field;
                    if (current == null) {
                        current = (LuaClassField) Proxy.newProxyInstance(
                                LuaClassField.class.getClassLoader(),
                                new Class<?>[]{LuaClassField.class},
                                this
                        );
                        field = current;
                    }
                }
            }
            return current;
        }

        private VirtualFile virtualFile() {
            PsiFile file = call.getContainingFile();
            return file != null ? file.getVirtualFile() : null;
        }

        private ITy inferType(SearchContext context) {
            // Preserve an explicit @class/@type when the module author supplied one.
            if (localDef != null) {
                LuaComment comment = localDef.getComment();
                if (comment != null) {
                    ITy annotatedType = PsiExtensionKt.getTy(comment);
                    if (annotatedType != null && !Ty.Companion.isInvalid(annotatedType)) {
                        return annotatedType;
                    }
                }
            }
            if (assignedName != null) {
                ITy annotatedType = PsiExtensionKt.getDocTy(assignedName);
                if (annotatedType != null && !Ty.Companion.isInvalid(annotatedType)) {
                    return annotatedType;
                }
            }

            // Keep the synthetic BaseClass subtype for inherited members, and also include EmmyLua's
            // anonymous type for the receiving local variable. Declarations such as function m.main()
            // are indexed under that anonymous type.
            return unannotatedModuleType(name, assignedName);
        }

        private static ITy unannotatedModuleType(String moduleName, LuaNameDef assignedName) {
            ITy type = syntheticType(moduleName);
            if (assignedName != null) {
                type = type.union(TyClass.Companion.createAnonymousType(assignedName));
            }
            return type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (methodName) {
                    case "toString" -> "Module." + name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }

            return switch (methodName) {
                case "getName" -> name;
                case "getNameIdentifier" -> nameElement;
                case "setName" -> nameElement;
                case "guessType" -> inferType((SearchContext) args[0]);
                case "guessParentType" -> MODULE_TYPE;
                case "getVisibility" -> Visibility.PUBLIC;
                case "isDeprecated" -> false;
                case "getWorth" -> LuaClassMember.WORTH_DOC;
                case "getNavigationElement", "getOriginalElement" -> nameElement;
                case "getPresentation" -> null;
                case "navigate" -> invokeDelegateOrDefault(proxy, method, args);
                case "canNavigate", "canNavigateToSource" -> nameElement.isValid();
                default -> invokeDelegateOrDefault(proxy, method, args);
            };
        }

        private Object invokeDelegateOrDefault(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return invokeDelegate(method, args);
            } catch (NoSuchMethodException ignored) {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
                }
                Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive()) {
                    return null;
                }
                if (returnType == boolean.class) return false;
                if (returnType == char.class) return '\0';
                if (returnType == byte.class) return (byte) 0;
                if (returnType == short.class) return (short) 0;
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                if (returnType == float.class) return 0F;
                if (returnType == double.class) return 0D;
                return null;
            }
        }

        private Object invokeDelegate(Method interfaceMethod, Object[] args) throws Throwable {
            Method delegateMethod = nameElement.getClass().getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
            try {
                return delegateMethod.invoke(nameElement, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
