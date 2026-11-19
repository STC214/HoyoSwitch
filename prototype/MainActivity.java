package dev.hoyoswitch.fixture;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private boolean switching;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String label = "Invalid test configuration";
        try (InputStream input = openFileInput("profile.txt")) {
            int account = input.read();
            if ((account == 'A' || account == 'B') && input.read() == -1) {
                label = "Test account " + (char) account;
            }
        } catch (FileNotFoundException absent) {
            label = "Test account A";
        } catch (IOException unreadable) {
            label = "Unreadable test configuration";
        }
        TextView title = new TextView(this);
        title.setText(label);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(32);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        panel.addView(title, -1, -2);

        SharedPreferences options = getSharedPreferences("fixture_options", MODE_PRIVATE);
        Switch reopen = new Switch(this);
        reopen.setText("切换后自动打开");
        reopen.setChecked(options.getBoolean("reopen", true));
        reopen.setOnCheckedChangeListener((button, checked) -> options.edit().putBoolean("reopen", checked).apply());
        for (String account : new String[] {"A", "B"}) {
            Button button = new Button(this);
            button.setText("切换到 " + account);
            button.setOnClickListener(view -> {
                if (switching) return;
                switching = true;
                // Ensure the option reaches disk before this process is stopped.
                if (!options.edit().putBoolean("reopen", reopen.isChecked()).commit()) {
                    switching = false;
                    title.setText("保存设置失败，请重试");
                    return;
                }
                startActivity(new Intent(this, SwitchActivity.class)
                        .putExtra("account", account)
                        .putExtra("oldPid", Process.myPid())
                        .putExtra("reopen", reopen.isChecked()));
            });
            panel.addView(button, -1, -2);
        }
        panel.addView(reopen, -1, -2);
        setContentView(panel);
    }
}
