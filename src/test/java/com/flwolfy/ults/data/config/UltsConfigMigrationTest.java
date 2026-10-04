package com.flwolfy.ults.data.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsConfigMigrationTest {
  private static UltsConfigData decode(JsonObject root) {
    return UltsConfigManager.decode(root, Set.of("en_us"));
  }

  private static JsonObject input(String fields) {
    return JsonParser.parseString("{\"input\":{" + fields + "}}").getAsJsonObject();
  }

  @Test void legacyValuesSurviveMigrationAndAreWrittenUnderOnlyNewNames() {
    var root = input("\"allowTakeAll\":false,\"takeAllStacks\":120,\"takeAllRate\":512");
    var migrated = decode(root);
    assertFalse(migrated.input().allowBulkWithdrawal());
    assertEquals(120, migrated.input().bulkWithdrawalStacks());
    assertEquals(512, migrated.input().withdrawalRate());
    var serialized = new Gson().toJsonTree(migrated).getAsJsonObject().getAsJsonObject("input");
    for (String retired : new String[]{"allowTakeAll", "takeAllStacks", "takeAllRate"}) {
      assertFalse(root.getAsJsonObject("input").has(retired));
      assertFalse(serialized.has(retired));
    }
    assertTrue(serialized.has("allowBulkWithdrawal"));
    assertTrue(serialized.has("bulkWithdrawalStacks"));
    assertTrue(serialized.has("withdrawalRate"));
    assertEquals(migrated, decode(new Gson().toJsonTree(migrated).getAsJsonObject()));
  }

  @Test void newNamesTakePrecedenceRegardlessOfJsonFieldOrder() {
    String oldFields = "\"allowTakeAll\":true,\"takeAllStacks\":120,\"takeAllRate\":512";
    String newFields = "\"allowBulkWithdrawal\":false,\"bulkWithdrawalStacks\":72,\"withdrawalRate\":17";
    for (String fields : new String[]{oldFields + "," + newFields, newFields + "," + oldFields}) {
      var decoded = decode(input(fields));
      assertFalse(decoded.input().allowBulkWithdrawal());
      assertEquals(72, decoded.input().bulkWithdrawalStacks());
      assertEquals(17, decoded.input().withdrawalRate());
    }
  }

  @Test void nullNewNamesInheritLegacyValuesBeforeDefaultsAreFilled() {
    var decoded = decode(input("\"withdrawalRate\":null,\"takeAllRate\":8,"
        + "\"allowBulkWithdrawal\":null,\"allowTakeAll\":false,"
        + "\"bulkWithdrawalStacks\":null,\"takeAllStacks\":9"));
    assertEquals(8, decoded.input().withdrawalRate());
    assertFalse(decoded.input().allowBulkWithdrawal());
    assertEquals(9, decoded.input().bulkWithdrawalStacks());
  }

  @Test void oldAndNewConfigurationsWithMissingOptionsKeepExistingDefaults() {
    for (String fields : new String[]{"", "\"takeAllStacks\":36", "\"bulkWithdrawalStacks\":36"}) {
      assertEquals(UltsConfigData.DEFAULT, decode(input(fields)));
    }
  }

  @Test void invalidLegacyLimitsAreReportedByTheirNewNames() {
    var invalidRate = assertThrows(IllegalArgumentException.class,
        () -> decode(input("\"takeAllRate\":0")));
    assertTrue(invalidRate.getMessage().contains("input.withdrawalRate"));
    var invalidStacks = assertThrows(IllegalArgumentException.class,
        () -> decode(input("\"takeAllStacks\":1000001")));
    assertTrue(invalidStacks.getMessage().contains("input.bulkWithdrawalStacks"));
  }

  @Test void invalidExplicitNewValuesDoNotSilentlyFallBackToValidLegacyValues() {
    assertThrows(IllegalArgumentException.class,
        () -> decode(input("\"withdrawalRate\":0,\"takeAllRate\":64")));
  }
}
