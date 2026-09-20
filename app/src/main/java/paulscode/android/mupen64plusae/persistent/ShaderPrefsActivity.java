/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * Copyright (C) 2013 Paul Lamb
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 *
 * Authors: littleguy77
 */
package paulscode.android.mupen64plusae.persistent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import paulscode.android.mupen64plusae.game.SlangParameter;
import paulscode.android.mupen64plusae.game.SlangPresetStore;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import paulscode.android.mupen64plusae.R;

import java.util.ArrayList;

import paulscode.android.mupen64plusae.compat.AppCompatPreferenceActivity;
import paulscode.android.mupen64plusae.game.ShaderLoader;
import paulscode.android.mupen64plusae.preference.PrefUtil;
import paulscode.android.mupen64plusae.preference.SeekBarPreference;
import paulscode.android.mupen64plusae.preference.ShaderPreference;
import paulscode.android.mupen64plusae.util.LocaleContextWrapper;

public class ShaderPrefsActivity extends AppCompatPreferenceActivity implements OnSharedPreferenceChangeListener, Preference.OnPreferenceClickListener, ShaderPreference.OnRemove {
    // These constants must match the keys used in res/xml/preferences.xml
    private static final String SCREEN_ROOT = "screenRoot";
    private static final String SCALE_FACTOR = "shaderScaleFactor";
    private static final String CATEGORY_PASSES = "categoryShaderPasses";
    private static final String ADD_PREFERENCE = "addShader";
    private static final String SHADER_PASS_KEY = "shaderpass,";
    private static final String SLANG_FOLDER = "slangPresetFolder";
    private static final String SLANG_PARAMETERS = "slangParameters,";
    private int mPendingSlangPass = -1;
    private Uri mBrowseTree;
    private boolean mRefreshing;
    private int mSlangRequest;
    private AlertDialog mSlangProgress;
    private java.util.concurrent.Future<?> mSlangTask;
    private final java.util.concurrent.ExecutorService mSlangWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Intent> mSlangFolderPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() != RESULT_OK || data == null || data.getData() == null) {
                    mPendingSlangPass = -1;
                    return;
                }
                Uri tree = data.getData();
                try {
                    // Acquire permission at the picker boundary, before any asynchronous reads.
                    int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(tree, flags);
                    SlangPresetStore.rememberFolder(this, tree);
                    if (mPendingSlangPass >= 0) showPresets(tree);
                    else Toast.makeText(this, R.string.shadersSlangFolderSaved, Toast.LENGTH_SHORT).show();
                } catch (SecurityException e) { showSlangError(e); }
            });

    // App data and user preferences
    private AppData mAppData = null;
    private GlobalPrefs mGlobalPrefs = null;
    private SharedPreferences mPrefs = null;
    private PreferenceGroup mCategoryPasses = null;
    private SeekBarPreference mScaleFactor = null;

    static final int MAX_SHADER_PASSES = 5;

    @Override
    protected void attachBaseContext(Context newBase) {
        if(TextUtils.isEmpty(LocaleContextWrapper.getLocalCode()))
        {
            super.attachBaseContext(newBase);
        }
        else
        {
            super.attachBaseContext(LocaleContextWrapper.wrap(newBase,LocaleContextWrapper.getLocalCode()));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        Log.i("Shader", "onCreate");

        // Get app data and user preferences
        mAppData = new AppData(this);
        mGlobalPrefs = new GlobalPrefs(this, mAppData);

        mPrefs = getSharedPreferences( "ShaderPrefs", MODE_PRIVATE );
        if (savedInstanceState != null) {
            mPendingSlangPass = savedInstanceState.getInt("slangPass", -1);
            String tree = savedInstanceState.getString("slangBrowseTree");
            if (tree != null) mBrowseTree = Uri.parse(tree);
        }
        super.onCreate(savedInstanceState);
        if (mBrowseTree != null && mPendingSlangPass >= 0)
            getWindow().getDecorView().post(() -> showPresets(mBrowseTree));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("slangPass", mPendingSlangPass);
        if (mBrowseTree != null) state.putString("slangBrowseTree", mBrowseTree.toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        ++mSlangRequest;
        mSlangWorker.shutdownNow();
        if (mSlangProgress != null) mSlangProgress.dismiss();
        super.onDestroy();
    }

    @Override
    protected String getSharedPrefsName() {
        return "ShaderPrefs";
    }

    @Override
    protected int getSharedPrefsId()
    {
        return R.xml.preferences_shader;
    }

    @Override
    protected void onPause()
    {
        super.onPause();

        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onResume()
    {
        Log.i("Shader", "onResume");

        super.onResume();
        // Just refresh the preference screens in place
        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
    {
        if (mRefreshing) return;
        Log.i("Shader", "onSharedPreferenceChanged: key=" + key);

        ArrayList<ShaderLoader> shaderPasses = mGlobalPrefs.getShaderPasses();

        if (key != null && key.equals(SCALE_FACTOR)) {
            mGlobalPrefs.putShaderScaleFactor(mScaleFactor.getValue());
        }

        if (key != null && key.startsWith(SHADER_PASS_KEY)) {
            String value = mPrefs.getString(key, ShaderLoader.DEFAULT.toString());

            String[] currentPassSplitString = key.split(",");

            if (currentPassSplitString.length == 2) {
                ShaderLoader valueEnum;
                try {
                    valueEnum = ShaderLoader.valueOf(value);
                } catch (java.lang.IllegalArgumentException e) {
                    valueEnum = null;
                }

                int changedPass = Integer.parseInt(currentPassSplitString[1]) - 1;

                if (changedPass >= 0 && changedPass < shaderPasses.size() && valueEnum != null) {
                    shaderPasses.set(changedPass, valueEnum);
                    mGlobalPrefs.putShaderPasses(shaderPasses);

                    refreshViews();
                }
            }
        }
    }

    private void refreshViews()
    {
        mRefreshing = true;
        try {
            Log.i("Shader", "refreshViews");

            // Refresh the preferences object
            mGlobalPrefs = new GlobalPrefs(this, mAppData);
            PreferenceGroup screenRoot = (PreferenceGroup) findPreference(SCREEN_ROOT);
            PreferenceGroup categoryPasses = (PreferenceGroup) findPreference(CATEGORY_PASSES);
            mScaleFactor = (SeekBarPreference) findPreference(SCALE_FACTOR);
            mScaleFactor.setValue(mGlobalPrefs.shaderScaleFactor);

            if (mCategoryPasses != null) {
                mCategoryPasses.removeAll();
            }

            mPrefs.edit().clear().apply();

            ArrayList<ShaderLoader> shaderPasses = mGlobalPrefs.getShaderPasses();

            for (int index = 0; index < shaderPasses.size(); ++index) {
                addShaderPass(shaderPasses.get(index), index + 1);
            }

            // If there are no shaders, then remove the category
            if (mCategoryPasses != null) {
                if (shaderPasses.isEmpty()) {
                    screenRoot.removePreference(mCategoryPasses);
                } else if (categoryPasses == null) {
                    screenRoot.addPreference(mCategoryPasses);
                }
            }
        } finally { mRefreshing = false; }
    }

    @Override
    protected void OnPreferenceScreenChange(String key)
    {
        Log.i("Shader", "OnPreferenceScreenChange");
        mCategoryPasses = (PreferenceGroup) findPreference( CATEGORY_PASSES );
        mScaleFactor = (SeekBarPreference) findPreference(SCALE_FACTOR);

        if (mCategoryPasses == null) {
            resetPreferences();
        } else {
            PrefUtil.setOnPreferenceClickListener(this, ADD_PREFERENCE, this);
            PrefUtil.setOnPreferenceClickListener(this, SLANG_FOLDER, this);
            refreshViews();
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {

        // Handle the clicks on certain menu items that aren't actually
        // preferences
        final String key = preference.getKey();

        if (SLANG_FOLDER.equals(key)) {
            cancelSlangSelection();
            chooseShaderFolder();
            return true;
        }

        if (ADD_PREFERENCE.equals(key)) {
            ArrayList<ShaderLoader> shaderPasses = mGlobalPrefs.getShaderPasses();

            if (shaderPasses.size() < MAX_SHADER_PASSES) {
                shaderPasses.add(ShaderLoader.DEFAULT);
                mGlobalPrefs.putShaderPasses(shaderPasses);
                refreshViews();
            }
        }

        // Tell Android that we handled the click
        return true;
    }

    private void chooseShaderFolder() {
        mBrowseTree = null;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri last = SlangPresetStore.lastFolder(this);
        if (last != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, last);
        mSlangFolderPicker.launch(intent);
    }

    private boolean onSelectShader(String key, String value) {
        if (!ShaderLoader.CUSTOM_SLANG.toString().equals(value)) return false;
        mPendingSlangPass = Integer.parseInt(key.substring(SHADER_PASS_KEY.length())) - 1;
        SlangPresetStore.Selection previous = SlangPresetStore.selection(this, mPendingSlangPass);
        Uri tree = previous != null ? previous.tree : SlangPresetStore.lastFolder(this);
        // Run after the shader-choice dialog has dismissed, even when Custom was already selected.
        getWindow().getDecorView().post(() -> {
            if (tree == null) chooseShaderFolder();
            else showPresets(tree);
        });
        return true;
    }

    private void showPresets(Uri tree) {
        if (tree == null || isFinishing() || isDestroyed()) return;
        mBrowseTree = tree;
        List<String> cached = SlangPresetStore.cachedPresetPaths(this, tree);
        if (cached != null) {
            if (mSlangTask != null) mSlangTask.cancel(true);
            ++mSlangRequest;
            if (mSlangProgress != null) { mSlangProgress.dismiss(); mSlangProgress = null; }
            showPresetResults(tree, cached);
            return;
        }
        long generation = SlangPresetStore.presetIndexGeneration(this);
        int request = beginSlangWork(R.string.shadersSlangSearching);
        // Document providers may be remote; keep traversal and shader reads off the UI thread.
        mSlangTask = mSlangWorker.submit(() -> {
            try {
                ArrayList<String> paths = new ArrayList<>();
                DocumentFile root = DocumentFile.fromTreeUri(this, tree);
                if (root == null || !root.canRead()) throw new IOException("Shader folder is unavailable. Choose the folder again to grant access.");
                findPresets(root, "", paths, 0, new int[] {0});
                Collections.sort(paths);
                if (Thread.currentThread().isInterrupted()) return;
                SlangPresetStore.cachePresetPaths(this, tree, paths, generation);
                runOnUiThread(() -> {
                    if (!finishSlangWork(request)) return;
                    showPresetResults(tree, paths);
                });
            } catch (IOException | RuntimeException | LinkageError e) { failSlangWork(request, e); }
        });
    }

    private void showPresetResults(Uri tree, List<String> paths) {
        if (paths.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(R.string.shadersSlangEmpty)
                    .setPositiveButton(R.string.shadersSlangChangeFolder, (d, w) -> chooseShaderFolder())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> cancelSlangSelection())
                    .setOnCancelListener(d -> cancelSlangSelection()).show();
        } else showPresetPicker(tree, paths);
    }

    private void showPresetPicker(Uri tree, List<String> paths) {
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.SearchView search = new android.widget.SearchView(this);
        search.setIconifiedByDefault(false);
        search.setQueryHint(getString(R.string.shadersSlangSearch));
        content.addView(search);

        android.widget.FrameLayout results = new android.widget.FrameLayout(this);
        int height = Math.min((int) (320 * getResources().getDisplayMetrics().density),
                getResources().getDisplayMetrics().heightPixels / 2);
        content.addView(results, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height));
        android.widget.ListView list = new android.widget.ListView(this);
        results.addView(list);
        android.widget.TextView empty = new android.widget.TextView(this);
        empty.setText(R.string.shadersSlangNoMatches);
        empty.setGravity(android.view.Gravity.CENTER);
        results.addView(empty, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>(paths));
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.shadersSlangChoose)
                .setView(content)
                .setNeutralButton(R.string.shadersSlangChangeFolder, (d, w) -> chooseShaderFolder())
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelSlangSelection())
                .setOnCancelListener(d -> cancelSlangSelection()).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String path = adapter.getItem(position);
            dialog.dismiss();
            selectPreset(tree, path);
        });
        search.setOnQueryTextListener(new android.widget.SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) {
                search.clearFocus();
                return true;
            }

            @Override public boolean onQueryTextChange(String query) {
                String filter = query.trim().toLowerCase(Locale.ROOT);
                ArrayList<String> matches = new ArrayList<>();
                for (String path : paths) if (path.toLowerCase(Locale.ROOT).contains(filter)) matches.add(path);
                adapter.setNotifyOnChange(false);
                adapter.clear();
                adapter.addAll(matches);
                adapter.notifyDataSetChanged();
                list.setSelection(0);
                return true;
            }
        });
        dialog.show();
        search.clearFocus();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private static void findPresets(DocumentFile directory, String prefix, ArrayList<String> paths, int depth, int[] visited) throws IOException {
        if (depth > 16) throw new IOException("Shader folder nesting exceeds 16 levels");
        for (DocumentFile file : directory.listFiles()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Preset search cancelled");
            if (++visited[0] > 10000) throw new IOException("Choose a smaller shader folder (at most 10000 entries)");
            String name = file.getName();
            if (name == null) continue;
            if (file.isDirectory()) findPresets(file, prefix + name + "/", paths, depth + 1, visited);
            else if (name.toLowerCase(Locale.ROOT).endsWith(".slangp")) paths.add(prefix + name);
        }
    }

    private void selectPreset(Uri tree, String path) {
        int pass = mPendingSlangPass;
        if (pass < 0) {
            // The library-folder shortcut only chooses a folder. Presets belong to a shader pass.
            Toast.makeText(this, R.string.shadersSlangSelectPass, Toast.LENGTH_LONG).show();
            cancelSlangSelection();
            return;
        }
        int request = beginSlangWork(R.string.shadersSlangValidating);
        mSlangTask = mSlangWorker.submit(() -> {
            try {
                SlangPresetStore.inspect(this, tree, path);
                runOnUiThread(() -> {
                    if (!finishSlangWork(request)) return;
                    ArrayList<ShaderLoader> passes = mGlobalPrefs.getShaderPasses();
                    if (pass >= passes.size()) return;
                    SlangPresetStore.save(this, pass, tree, path);
                    passes.set(pass, ShaderLoader.CUSTOM_SLANG);
                    mGlobalPrefs.putShaderPasses(passes);
                    cancelSlangSelection();
                    refreshViews();
                    Toast.makeText(this, R.string.shadersSlangSaved, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException | RuntimeException | LinkageError e) { failSlangWork(request, e); }
        });
    }

    private void cancelSlangSelection() {
        if (mSlangTask != null) mSlangTask.cancel(true);
        mPendingSlangPass = -1;
        mBrowseTree = null;
        ++mSlangRequest;
    }

    private int beginSlangWork(int message) {
        if (mSlangTask != null) mSlangTask.cancel(true);
        if (mSlangProgress != null) mSlangProgress.dismiss();
        int request = ++mSlangRequest;
        mSlangProgress = new AlertDialog.Builder(this).setMessage(message)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelSlangSelection())
                .setOnCancelListener(d -> cancelSlangSelection()).create();
        mSlangProgress.show();
        return request;
    }

    private boolean finishSlangWork(int request) {
        if (isFinishing() || isDestroyed() || request != mSlangRequest) return false;
        if (mSlangProgress != null) { mSlangProgress.dismiss(); mSlangProgress = null; }
        return true;
    }

    private void failSlangWork(int request, Throwable error) {
        runOnUiThread(() -> {
            if (!finishSlangWork(request)) return;
            Log.e("Shader", "Slang preset operation failed", error);
            new AlertDialog.Builder(this).setMessage(getString(R.string.shadersSlangError, error.getMessage()))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.shadersSlangChangeFolder, (d, w) -> chooseShaderFolder()).show();
        });
    }

    private void showParameters(int pass) {
        SlangPresetStore.Selection selection = SlangPresetStore.selection(this, pass);
        if (selection == null) return;
        int request = beginSlangWork(R.string.shadersSlangLoadingParameters);
        mSlangTask = mSlangWorker.submit(() -> {
            try {
                // Read defaults independently of overrides, so Reset can recover stale saved values.
                List<SlangParameter> parameters = SlangPresetStore.inspect(this, selection.tree, selection.path);
                runOnUiThread(() -> {
                    if (!finishSlangWork(request)) return;
                    Map<String, String> values = SlangPresetStore.overrides(this, selection);
                    String[] labels = new String[parameters.size()];
                    for (int i = 0; i < parameters.size(); i++) {
                        SlangParameter parameter = parameters.get(i);
                        labels[i] = parameter.label + "  =  " + values.getOrDefault(parameter.id, Float.toString(parameter.defaultValue));
                    }
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.shadersSlangParameters)
                            .setNegativeButton(android.R.string.cancel, null);
                    if (parameters.isEmpty()) dialog.setMessage(R.string.shadersSlangNoParameters);
                    else dialog.setItems(labels, (d, which) -> editParameter(pass, selection, parameters.get(which)))
                            .setNeutralButton(R.string.shadersSlangReset, (d, w) -> {
                                SlangPresetStore.saveParameters(this, selection, Collections.emptyMap());
                                showParameters(pass);
                            });
                    dialog.show();
                });
            } catch (IOException | RuntimeException | LinkageError e) { failSlangWork(request, e); }
        });
    }

    private void editParameter(int pass, SlangPresetStore.Selection selection, SlangParameter parameter) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(SlangPresetStore.overrides(this, selection).getOrDefault(parameter.id, Float.toString(parameter.defaultValue)));
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(parameter.label)
                .setMessage(getString(R.string.shadersSlangParameterRange, Float.toString(parameter.minimum),
                        Float.toString(parameter.maximum), Float.toString(parameter.step)))
                .setView(input).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, (d, w) -> showParameters(pass))
                .setNeutralButton(R.string.shadersSlangReset, (d, w) -> {
                    Map<String, String> values = SlangPresetStore.overrides(this, selection);
                    values.remove(parameter.id);
                    SlangPresetStore.saveParameters(this, selection, values);
                    showParameters(pass);
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                float value = parameter.editedValue(input.getText().toString());
                Map<String, String> values = SlangPresetStore.overrides(this, selection);
                values.put(parameter.id, Float.toString(value));
                SlangPresetStore.saveParameters(this, selection, values);
                dialog.dismiss();
                showParameters(pass);
            } catch (IOException e) { input.setError(e.getMessage()); }
        }));
        dialog.show();
    }

    private void showSlangError(Exception error) {
        Log.e("Shader", "Slang preset import failed", error);
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed())
                Toast.makeText(this, getString(R.string.shadersSlangError, error.getMessage()), Toast.LENGTH_LONG).show();
        });
    }

    public void addShaderPass(ShaderLoader shader, int shaderPass) {
        if (mCategoryPasses != null) {
            ShaderPreference preference = new ShaderPreference(getPreferenceManagerContext());
            String key = SHADER_PASS_KEY + shaderPass;
            preference.setKey(key);
            preference.populateShaderOptions(this);
            String title = getString(R.string.shadersPass_title) + " " + shaderPass;
            preference.setTitle(title);
            preference.setSummary(shader.getFriendlyName());
            if (shader == ShaderLoader.CUSTOM_SLANG) {
                SlangPresetStore.Selection selected = SlangPresetStore.selection(this, shaderPass - 1);
                if (selected != null) preference.setSummary(selected.path);
            }
            preference.setValue(shader.toString());
            preference.setOnRemoveCallback(this);
            preference.setOnSelectCallback(this::onSelectShader);

            mCategoryPasses.addPreference(preference);
            if (shader == ShaderLoader.CUSTOM_SLANG) {
                Preference parameters = new Preference(getPreferenceManagerContext());
                parameters.setKey(SLANG_PARAMETERS + (shaderPass - 1));
                parameters.setPersistent(false);
                parameters.setTitle(getString(R.string.shadersSlangPassParameters, shaderPass));
                parameters.setSummary(R.string.shadersSlangParametersSummary);
                parameters.setOnPreferenceClickListener(p -> { showParameters(shaderPass - 1); return true; });
                mCategoryPasses.addPreference(parameters);
            }
        }
    }

    @Override
    public void onRemove(String key) {
        String[] currentPassSplitString = key.split(",");

        if (currentPassSplitString.length == 2) {
            ArrayList<ShaderLoader> shaderPasses = mGlobalPrefs.getShaderPasses();
            int changedPass = Integer.parseInt(currentPassSplitString[1]) - 1;

            if (changedPass >= 0 && changedPass < shaderPasses.size()) {
                SlangPresetStore.removePass(this, changedPass, shaderPasses.size());
                shaderPasses.remove(changedPass);
                mGlobalPrefs.putShaderPasses(shaderPasses);
                refreshViews();
            }
        }
    }
}
