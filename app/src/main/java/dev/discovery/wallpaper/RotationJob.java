package dev.discovery.wallpaper;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RotationJob extends JobService {
    static final int JOB_ID = 2301;
    private final Map<Integer, AtomicBoolean> jobs = new HashMap<>();

    static boolean sync(Context context, Discovery.State state) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!state.automatic) {
            scheduler.cancel(JOB_ID);
            return true;
        }
        long interval = TimeUnit.MINUTES.toMillis(state.minutes);
        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        if (pending != null && pending.getIntervalMillis() == interval) return true;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, RotationJob.class))
                .setPeriodic(interval).setPersisted(true).build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    @Override
    public boolean onStartJob(JobParameters parameters) {
        Log.i(WallpaperDelivery.TAG, "Scheduled rotation started");
        int id = parameters.getJobId();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean previous = jobs.put(id, cancelled);
        if (previous != null) previous.set(true);
        WallpaperDelivery.run(this, WallpaperDelivery.Trigger.SCHEDULED, null, cancelled::get, message -> {
            if (jobs.remove(id, cancelled)) jobFinished(parameters, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        Log.i(WallpaperDelivery.TAG, "Scheduled rotation stopped"
                + (Build.VERSION.SDK_INT >= 31 ? ", reason=" + parameters.getStopReason() : ""));
        AtomicBoolean cancelled = jobs.remove(parameters.getJobId());
        if (cancelled != null) cancelled.set(true);
        return false;
    }
}
