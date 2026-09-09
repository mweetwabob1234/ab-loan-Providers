package zm.ablender.loanbook;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Fires at 10am (see ReminderScheduler) to notify about loans due that day,
 * and reschedules itself for the following day. Also reschedules after a
 * device reboot, since AlarmManager alarms do not survive one.
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    checkDueLoansAndNotify(context.getApplicationContext());
                }
                ReminderScheduler.scheduleNext(context.getApplicationContext());
            } finally {
                pending.finish();
            }
        }).start();
    }

    private void checkDueLoansAndNotify(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        List<LoanSync.Loan> loans = LoanSync.fetchLoans();
        if (loans == null) return;

        String today = LoanSync.todayIso();
        int id = 2000;
        for (LoanSync.Loan l : loans) {
            if (!l.settled && today.equals(l.end)) {
                showNotification(context, id++, l.first + " " + l.last + " is due today");
            }
        }
    }

    private void showNotification(Context context, int id, String text) {
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(context, id, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Loan due today")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException ignored) {
            // Permission was revoked between the check above and here; skip quietly.
        }
    }
}
