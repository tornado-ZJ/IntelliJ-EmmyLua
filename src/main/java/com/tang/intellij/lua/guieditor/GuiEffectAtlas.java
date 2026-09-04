package com.tang.intellij.lua.guieditor;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class GuiEffectAtlas {
    record Frame(BufferedImage image, int offsetX, int offsetY) { }

    static List<Frame> read(Path plist, int direction, boolean sfx) {
        List<Frame> result = new ArrayList<>();
        try {
            Path png = Path.of(plist.toString().replaceFirst("(?i)\\.plist$", ".png"));
            if (!Files.isRegularFile(png)) return result;
            BufferedImage atlas = readImage(png);
            if (atlas == null) return result;
            return read(plist, atlas, direction, sfx);
        } catch (Exception error) { if (Boolean.getBoolean("emmy.gui.effect.debug")) error.printStackTrace(); }
        return result;
    }

    static List<Frame> read(Path plist, BufferedImage atlas, int direction, boolean sfx) {
        List<Frame> result = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) { }
            Element root = factory.newDocumentBuilder().parse(plist.toFile()).getDocumentElement();
            Element top = firstChild(root, "dict"), frames = top == null ? null : dictValue(top, "frames");
            if (frames == null) return result;
            List<Element> entries = elementChildren(frames);
            for (int i = 0; i + 1 < entries.size(); i += 2) {
                String frameName = entries.get(i).getTextContent().trim();
                Element data = entries.get(i + 1);
                if (!sfx && !frameDirectionMatches(frameName, direction)) continue;
                int[] rect = numbers(dictText(data, "frame"), 4);
                int[] offset = numbers(dictText(data, "offset"), 2);
                int[] source = numbers(dictText(data, "sourceSize"), 2);
                if (rect.length < 4 || source.length < 2) continue;
                boolean rotated = dictBoolean(data, "rotated");
                int x = rect[0], y = rect[1], width = rect[2], height = rect[3];
                // TexturePacker stores a rotated rectangle with its packed width/height exchanged.
                int packedWidth = rotated ? height : width, packedHeight = rotated ? width : height;
                if (x < 0 || y < 0 || packedWidth < 1 || packedHeight < 1 || x + packedWidth > atlas.getWidth() || y + packedHeight > atlas.getHeight()) continue;
                BufferedImage crop = copyImage(atlas.getSubimage(x, y, packedWidth, packedHeight));
                if (rotated) crop = rotateCounterClockwise(crop);
                BufferedImage canvas = new BufferedImage(Math.max(1, source[0]), Math.max(1, source[1]), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = canvas.createGraphics();
                int ox = offset.length > 0 ? offset[0] : 0, oy = offset.length > 1 ? offset[1] : 0;
                // The original editor centers the trimmed frame here. The plist offset is
                // applied later as a WPF layout margin, so baking it into this bitmap clips it.
                int dx = (canvas.getWidth() - crop.getWidth()) / 2;
                int dy = (canvas.getHeight() - crop.getHeight()) / 2;
                graphics.drawImage(crop, dx, dy, null);
                graphics.dispose();
                result.add(new Frame(canvas, ox, oy));
            }
        } catch (Exception error) { if (Boolean.getBoolean("emmy.gui.effect.debug")) error.printStackTrace(); }
        return result;
    }

    static BufferedImage readImage(Path path) {
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            input.mark(2); int first = input.read(), second = input.read(); input.reset();
            InputStream decoded = first == 0x1F && second == 0x8B ? new GZIPInputStream(input) : input;
            return ImageIO.read(decoded);
        } catch (Exception ignored) { return null; }
    }

    private static boolean frameDirectionMatches(String name, int direction) {
        String[] parts = name.replaceFirst("(?i)\\.png$", "").split("_");
        if (parts.length < 6) return true;
        try { return Integer.parseInt(parts[parts.length - 2]) == direction; }
        catch (Exception ignored) { return true; }
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics(); graphics.drawImage(source, 0, 0, null); graphics.dispose(); return copy;
    }

    private static BufferedImage rotateCounterClockwise(BufferedImage source) {
        BufferedImage rotated = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rotated.createGraphics(); graphics.translate(0, source.getWidth()); graphics.rotate(-Math.PI / 2); graphics.drawImage(source, 0, 0, null); graphics.dispose(); return rotated;
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element element && element.getTagName().equals(tag)) return element;
        return null;
    }

    private static List<Element> elementChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element element) result.add(element);
        return result;
    }

    private static Element dictValue(Element dict, String key) {
        List<Element> children = elementChildren(dict);
        for (int i = 0; i + 1 < children.size(); i++) if (children.get(i).getTagName().equals("key") && children.get(i).getTextContent().trim().equals(key)) return children.get(i + 1);
        return null;
    }

    private static String dictText(Element dict, String key) { Element value = dictValue(dict, key); return value == null ? "" : value.getTextContent().trim(); }
    private static boolean dictBoolean(Element dict, String key) { Element value = dictValue(dict, key); return value != null && value.getTagName().equals("true"); }
    private static int[] numbers(String value, int maximum) { Matcher matcher = Pattern.compile("-?\\d+").matcher(value); int[] values = new int[maximum]; int count = 0; while (count < maximum && matcher.find()) values[count++] = Integer.parseInt(matcher.group()); return count == maximum ? values : Arrays.copyOf(values, count); }

    private GuiEffectAtlas() { }
}
