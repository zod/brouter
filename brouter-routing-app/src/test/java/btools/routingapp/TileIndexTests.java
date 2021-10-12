package btools.routingapp;

import org.junit.Test;
import static org.junit.Assert.*;

public class TileIndexTests {
  @Test
  public void ConvertTileIndexToBaseName() {
    assertEquals("W180_S90", BInstallerView.baseNameForTile(0));
    assertEquals("E175_S90", BInstallerView.baseNameForTile(71));
    assertEquals("W180_S85", BInstallerView.baseNameForTile(72));

    assertEquals("E175_N85", BInstallerView.baseNameForTile((36 * 72) - 1));
  }

  @Test
  public void ConvertBaseNameToTileIndex() {
    assertEquals(0, BInstallerView.tileForBaseName("W180_S90"));
    assertEquals(71, BInstallerView.tileForBaseName("E175_S90"));
    assertEquals(72, BInstallerView.tileForBaseName("W180_S85"));

    assertEquals((36 * 72) - 1, BInstallerView.tileForBaseName("E175_N85"));
  }
}
