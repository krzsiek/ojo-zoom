package it.danieleverducci.ojo.ui;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

import it.danieleverducci.ojo.R;
import it.danieleverducci.ojo.Settings;
import it.danieleverducci.ojo.SharedPreferencesManager;
import it.danieleverducci.ojo.databinding.FragmentSettingsItemListBinding;
import it.danieleverducci.ojo.entities.Camera;
import it.danieleverducci.ojo.ui.adapters.SettingsRecyclerViewAdapter;
import it.danieleverducci.ojo.utils.ItemMoveCallback;

/**
 * A fragment representing a list of Items.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsItemListBinding binding;
    private Settings settings;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentSettingsItemListBinding.inflate(inflater, container, false);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Setup toolbar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding.settingsToolbar.getOverflowIcon().setTint(Color.WHITE);
        }
        binding.settingsToolbar.inflateMenu(R.menu.settings_menu);
        MenuItem rotMenuItem = binding.settingsToolbar.getMenu().findItem(R.id.menuitem_allow_rotation);
        rotMenuItem.setTitle(((SettingsActivity)getActivity()).getRotationEnabledSetting() ? R.string.menuitem_deny_rotation : R.string.menuitem_allow_rotation);
        MenuItem maxRecMenuItem = binding.settingsToolbar.getMenu().findItem(R.id.menuitem_max_recording);
        updateMaxRecordingTitle(maxRecMenuItem);

        // Register for item click
        binding.settingsToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menuitem_add_camera:
                        ((SettingsActivity)getActivity()).navigateToFragment(R.id.action_settingsToCameraUrl);
                        return true;
                    case R.id.menuitem_allow_rotation:
                        ((SettingsActivity)getActivity()).toggleRotationEnabledSetting();
                        SharedPreferencesManager.saveRotationEnabled(getContext(), ((SettingsActivity)getActivity()).getRotationEnabledSetting());
                        item.setTitle(((SettingsActivity)getActivity()).getRotationEnabledSetting() ? R.string.menuitem_deny_rotation : R.string.menuitem_allow_rotation);
                        return true;
                    case R.id.menuitem_max_recording:
                        showMaxRecordingDialog(item);
                        return true;
                    case R.id.menuitem_info:
                        ((SettingsActivity)getActivity()).navigateToFragment(R.id.action_SettingsToInfoFragment);
                        return true;
                }
                return false;
            }
        });
    }

    private void updateMaxRecordingTitle(MenuItem item) {
        item.setTitle(getString(R.string.menuitem_max_recording, SharedPreferencesManager.loadMaxRecordingMinutes(getContext())));
    }

    /**
     * Recordings stop by themselves after this time, so a forgotten recording can't fill the storage
     */
    private void showMaxRecordingDialog(MenuItem item) {
        int[] choices = SharedPreferencesManager.MAX_RECORDING_MINUTES_CHOICES;
        int current = SharedPreferencesManager.loadMaxRecordingMinutes(getContext());
        String[] labels = new String[choices.length];
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = getString(R.string.max_recording_minutes, choices[i]);
            if (choices[i] == current)
                checked = i;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.max_recording_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    SharedPreferencesManager.saveMaxRecordingMinutes(getContext(), choices[which]);
                    updateMaxRecordingTitle(item);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Load cameras
        settings = Settings.fromDisk(getContext());
        List<Camera> cams = settings.getCameras();

        // Set the adapter
        RecyclerView recyclerView = binding.list;
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        SettingsRecyclerViewAdapter adapter = new SettingsRecyclerViewAdapter(cams);
        ItemTouchHelper.Callback callback =
                new ItemMoveCallback(adapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);
        adapter.setOnDragListener(touchHelper::startDrag);
        recyclerView.setAdapter(adapter);
        // Onclick listener
        adapter.setOnClickListener(new SettingsRecyclerViewAdapter.OnClickListener() {
            @Override
            public void onItemClick(int pos) {
                Bundle b = new Bundle();
                b.putInt(StreamUrlFragment.ARG_CAMERA, pos);
                ((SettingsActivity)getActivity()).navigateToFragment(R.id.action_settingsToCameraUrl, b);
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();

        // Save cameras
        List<Camera> cams = ((SettingsRecyclerViewAdapter)binding.list.getAdapter()).getItems();
        this.settings.setCameras(cams);
        this.settings.save();
    }
}