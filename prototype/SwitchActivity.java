package dev.hoyoswitch.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.view.Gravity;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class SwitchActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setText("正在切换测试账号…");
        setContentView(status);
        if (state != null) {
            status.setText("切换已中断，请从桌面重开以核对账号");
            return;
        }
        String account = getIntent().getStringExtra("account");
        int oldPid = getIntent().getIntExtra("oldPid", -1);
        boolean reopen = getIntent().getBooleanExtra("reopen", true);
        new Thread(() -> {
            try {
                if (!("A".equals(account) || "B".equals(account)) || oldPid <= 0 || oldPid == Process.myPid()) {
                    throw new IOException("Invalid fixture switch request");
                }
                stopOldProcess(oldPid);
                saveAccount((byte) account.charAt(0));
                runOnUiThread(() -> {
                    if (reopen) {
                        startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        finish();
                    } else {
                        finishAndRemoveTask();
                    }
                });
            } catch (IOException | ErrnoException | SecurityException error) {
                runOnUiThread(() -> status.setText("切换测试账号失败，请从桌面重开核对"));
            }
        }, "fixture-switch").start();
    }

    private void stopOldProcess(int pid) throws IOException, ErrnoException {
        Process.killProcess(pid);
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                Os.kill(pid, 0);
            } catch (ErrnoException error) {
                if (error.errno == OsConstants.ESRCH) return;
                throw error;
            }
            SystemClock.sleep(20);
        }
        throw new IOException("Original fixture process still running");
    }

    private void saveAccount(byte account) throws IOException {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "profile.txt"));
        byte[] prior = file.getBaseFile().exists() ? file.readFully() : new byte[] {'A'};
        if (prior.length != 1 || (prior[0] != 'A' && prior[0] != 'B')) {
            throw new IOException("Existing fixture configuration invalid");
        }
        try {
            write(file, account);
            byte[] actual = file.readFully();
            if (actual.length != 1 || actual[0] != account) throw new IOException("Fixture readback mismatch");
        } catch (IOException error) {
            write(file, prior[0]);
            throw error;
        }
    }

    private void write(AtomicFile file, byte account) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(account);
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }
}
