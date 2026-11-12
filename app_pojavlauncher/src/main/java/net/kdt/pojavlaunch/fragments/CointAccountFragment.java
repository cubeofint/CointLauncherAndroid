package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.coint.auth.CointAccountModel;

public final class CointAccountFragment extends androidx.fragment.app.Fragment {
    public static final String TAG = "COINT_ACCOUNT";
    private boolean alreadySecure;
    public CointAccountFragment() { super(R.layout.fragment_coint_account); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        CointAccountModel model = new ViewModelProvider(requireActivity()).get(CointAccountModel.class);
        EditText login = view.findViewById(R.id.coint_login);
        EditText password = view.findViewById(R.id.coint_password);
        Button submit = view.findViewById(R.id.coint_sign_in);
        Button restore = view.findViewById(R.id.coint_restore);
        Button logout = view.findViewById(R.id.coint_sign_out);
        TextView status = view.findViewById(R.id.coint_account_status);
        TextView error = view.findViewById(R.id.coint_account_error);
        submit.setOnClickListener(v -> {
            String name = login.getText().toString().trim();
            String secret = password.getText().toString();
            if (name.isEmpty() || secret.isEmpty()) {
                error.setText(R.string.coint_auth_credentials);
                error.setVisibility(View.VISIBLE);
                return;
            }
            password.setText("");
            model.login(name, secret);
        });
        restore.setOnClickListener(v -> model.restore());
        logout.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setMessage(R.string.coint_logout_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.coint_sign_out, (dialog, which) -> model.logout()).show());
        model.state().observe(getViewLifecycleOwner(), state -> {
            boolean signedIn = state.username != null;
            status.setText(state.busy ? getString(R.string.coint_auth_busy)
                    : signedIn ? getString(state.verified ? R.string.coint_auth_verified : R.string.coint_auth_saved, state.username)
                    : getString(R.string.coint_auth_signed_out));
            login.setVisibility(signedIn ? View.GONE : View.VISIBLE);
            password.setVisibility(signedIn ? View.GONE : View.VISIBLE);
            submit.setVisibility(signedIn ? View.GONE : View.VISIBLE);
            restore.setVisibility(signedIn ? View.VISIBLE : View.GONE);
            // Also allow clearing an unreadable saved session after keystore invalidation.
            logout.setVisibility(signedIn || "storage".equals(state.error) ? View.VISIBLE : View.GONE);
            login.setEnabled(!state.busy);
            password.setEnabled(!state.busy);
            submit.setEnabled(!state.busy);
            restore.setEnabled(!state.busy);
            logout.setEnabled(!state.busy);
            view.findViewById(R.id.coint_auth_progress).setVisibility(state.busy ? View.VISIBLE : View.GONE);
            error.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
            if (state.error != null) error.setText(errorResource(state.error));
        });
    }

    @Override public void onResume() {
        super.onResume();
        alreadySecure = (requireActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    @Override public void onPause() {
        if (!alreadySecure) requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onPause();
    }
    @Override public void onDestroyView() {
        EditText password = requireView().findViewById(R.id.coint_password);
        password.setText("");
        super.onDestroyView();
    }

    private static int errorResource(String code) {
        switch (code) {
            case "auth.wrongpassword": case "auth.usernotfound": return R.string.coint_auth_bad_login;
            case "auth.userblocked": return R.string.coint_auth_blocked;
            case "auth.require2fa": return R.string.coint_auth_two_factor;
            case "auth.expiretoken": case "auth.invalidtoken": return R.string.coint_auth_expired;
            case "provider": return R.string.coint_auth_provider;
            case "network": return R.string.coint_auth_network;
            case "storage": return R.string.coint_auth_storage;
            case "credentials": return R.string.coint_auth_credentials;
            default: return R.string.coint_auth_failed;
        }
    }
}
