package com.aerogroup.mcpanel;

public class MapParserSmoke {
    public static void main(String[] args) {
        var pos = PlayerMapPane.parseConsoleLine("[Server thread/INFO]: Hasan_01 has the following entity data: [123.5d, 64.0d, -88.25d]");
        var dim = PlayerMapPane.parseConsoleLine("[Server thread/INFO]: Hasan_01 has the following entity data: \"minecraft:the_nether\"");
        if (pos == null || pos.x() != 123.5 || pos.z() != -88.25 || dim == null || !"minecraft:the_nether".equals(dim.dimension())) throw new IllegalStateException("map parser failed");
        System.out.println("map-parser-ok");
    }
}
