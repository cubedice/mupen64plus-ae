package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import androidx.appcompat.app.AlertDialog;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import paulscode.android.mupen64plusae.R;
import paulscode.android.mupen64plusae.preference.ShaderPreference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SlangPresetSettingsTest {
    private Context context;
    private Uri tree;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Ashaders");
        context.getSharedPreferences("SlangPreset", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void passesKeepIndependentPresetsAndParameterValues() {
        SlangPresetStore.save(context, 0, tree, "first.slangp");
        SlangPresetStore.save(context, 1, tree, "second.slangp");
        SlangPresetStore.Selection first = SlangPresetStore.selection(context, 0);
        SlangPresetStore.Selection second = SlangPresetStore.selection(context, 1);
        SlangPresetStore.saveParameters(context, first, Map.of("GAIN", "1.5"));
        SlangPresetStore.saveParameters(context, second, Map.of("GAIN", "0.5"));
        assertEquals("first.slangp", SlangPresetStore.selection(context, 0).path);
        assertEquals("1.5", SlangPresetStore.overrides(context, SlangPresetStore.selection(context, 0)).get("GAIN"));
        assertEquals("0.5", SlangPresetStore.overrides(context, SlangPresetStore.selection(context, 1)).get("GAIN"));
        // Re-selecting the same preset preserves the user's settings.
        SlangPresetStore.save(context, 0, tree, "first.slangp");
        assertEquals(first.id, SlangPresetStore.selection(context, 0).id);
        // A different preset starts with its own defaults.
        SlangPresetStore.save(context, 0, tree, "third.slangp");
        assertTrue(SlangPresetStore.overrides(context, SlangPresetStore.selection(context, 0)).isEmpty());
        assertEquals("0.5", SlangPresetStore.overrides(context, second).get("GAIN"));
    }

    @Test public void presetIndexSurvivesSameFolderAndPresetChanges() {
        SlangPresetStore.rememberFolder(context, tree);
        java.util.List<String> paths = java.util.Arrays.asList("crt/a.slangp", "lcd/b.slangp");
        SlangPresetStore.cachePresetPaths(context, tree, paths, SlangPresetStore.presetIndexGeneration(context));
        SlangPresetStore.rememberFolder(context, tree);
        SlangPresetStore.save(context, 0, tree, "crt/a.slangp");
        SlangPresetStore.save(context, 0, tree, "lcd/b.slangp");
        assertEquals(paths, SlangPresetStore.cachedPresetPaths(context, tree));
        // Reading the cache returns a separate list; filtering cannot alter the persisted index.
        SlangPresetStore.cachedPresetPaths(context, tree).clear();
        assertEquals(paths, SlangPresetStore.cachedPresetPaths(context, tree));
    }

    @Test public void newFolderInvalidatesIndexAndRejectsAnOldScan() {
        SlangPresetStore.rememberFolder(context, tree);
        long generation = SlangPresetStore.presetIndexGeneration(context);
        java.util.List<String> paths = java.util.Collections.singletonList("a.slangp");
        SlangPresetStore.cachePresetPaths(context, tree, paths, generation);
        Uri other = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Aother");
        SlangPresetStore.rememberFolder(context, other);
        assertNull(SlangPresetStore.cachedPresetPaths(context, tree));
        SlangPresetStore.cachePresetPaths(context, tree, paths, generation);
        assertNull(SlangPresetStore.cachedPresetPaths(context, tree));
        SlangPresetStore.cachePresetPaths(context, other, paths, SlangPresetStore.presetIndexGeneration(context));
        assertEquals(paths, SlangPresetStore.cachedPresetPaths(context, other));
    }

    @Test public void emptyFolderIsCachedRatherThanRescanned() {
        assertNull(SlangPresetStore.cachedPresetPaths(context, tree));
        SlangPresetStore.cachePresetPaths(context, tree, java.util.Collections.emptyList(), SlangPresetStore.presetIndexGeneration(context));
        assertNotNull(SlangPresetStore.cachedPresetPaths(context, tree));
        assertTrue(SlangPresetStore.cachedPresetPaths(context, tree).isEmpty());
    }

    @Test public void removingPassMovesSelectionAndParametersTogether() {
        SlangPresetStore.save(context, 1, tree, "second.slangp");
        SlangPresetStore.Selection second = SlangPresetStore.selection(context, 1);
        SlangPresetStore.saveParameters(context, second, Map.of("GAIN", "1.5"));
        SlangPresetStore.removePass(context, 0, 2);
        assertEquals(second.id, SlangPresetStore.selection(context, 0).id);
        assertEquals("1.5", SlangPresetStore.overrides(context, SlangPresetStore.selection(context, 0)).get("GAIN"));
        assertNull(SlangPresetStore.selection(context, 1));
        SlangPresetStore.saveParameters(context, second, Map.of());
        assertTrue(SlangPresetStore.overrides(context, second).isEmpty());
    }

    @Test public void oldGlobalPresetMigratesToIndependentPasses() {
        context.getSharedPreferences("SlangPreset", Context.MODE_PRIVATE).edit()
                .putString("tree", tree.toString()).putString("path", "legacy.slangp").commit();
        SlangPresetStore.Selection first = SlangPresetStore.selection(context, 0);
        SlangPresetStore.Selection second = SlangPresetStore.selection(context, 1);
        assertEquals("legacy.slangp", first.path);
        assertEquals(tree, first.tree);
        assertNotEquals(first.id, second.id);
    }

    @Test public void customSelectionCallbackRunsAgainEvenWhenAlreadySelected() {
        Context themed = new ContextThemeWrapper(context, R.style.MupenTheme_Dark);
        ShaderPreference preference = new ShaderPreference(themed);
        preference.setKey("shaderpass,1");
        preference.setPersistent(false);
        preference.setEntries(new String[] {"None", "Custom"});
        preference.setEntryValues(new String[] {"DEFAULT", "CUSTOM_SLANG"});
        preference.setValue("CUSTOM_SLANG");
        AtomicInteger selections = new AtomicInteger();
        preference.setOnSelectCallback((key, value) -> {
            assertEquals("shaderpass,1", key);
            assertEquals("CUSTOM_SLANG", value);
            selections.incrementAndGet();
            return true;
        });
        for (int i = 0; i < 2; i++) {
            AlertDialog.Builder builder = new AlertDialog.Builder(themed);
            preference.onPrepareDialogBuilder(themed, builder);
            AlertDialog dialog = builder.create();
            dialog.show();
            dialog.getListView().performItemClick(null, 1, 1);
        }
        assertEquals(2, selections.get());
    }

    @Test public void pickerCancellationDoesNotChangePreviousShaderChoice() {
        Context themed = new ContextThemeWrapper(context, R.style.MupenTheme_Dark);
        ShaderPreference preference = new ShaderPreference(themed);
        preference.setPersistent(false);
        preference.setEntries(new String[] {"None", "Custom"});
        preference.setEntryValues(new String[] {"DEFAULT", "CUSTOM_SLANG"});
        preference.setValue("DEFAULT");
        preference.setOnSelectCallback((key, value) -> true); // Wait for the file picker to commit.
        AlertDialog.Builder builder = new AlertDialog.Builder(themed);
        preference.onPrepareDialogBuilder(themed, builder);
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getListView().performItemClick(null, 1, 1);
        assertEquals("DEFAULT", preference.getValue());
    }
}
