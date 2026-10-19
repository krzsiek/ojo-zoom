package it.danieleverducci.ojo.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import it.danieleverducci.ojo.BuildConfig;
import it.danieleverducci.ojo.R;
import it.danieleverducci.ojo.databinding.FragmentInfoBinding;


public class InfoFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FragmentInfoBinding binding = FragmentInfoBinding.inflate(inflater, container, false);
        binding.appInfoVersion.setText(getString(R.string.app_info_version, BuildConfig.VERSION_NAME));
        return binding.getRoot();
    }
}
