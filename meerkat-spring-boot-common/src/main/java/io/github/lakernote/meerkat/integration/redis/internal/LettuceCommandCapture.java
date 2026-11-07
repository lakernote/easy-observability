package io.github.lakernote.meerkat.integration.redis.internal;

import io.github.lakernote.meerkat.capture.internal.CaptureText;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.observation.Observation;

/** Records a Lettuce command and its reply on a captured Redis span; only loaded when Lettuce is present. */
public final class LettuceCommandCapture {
    private LettuceCommandCapture() {
    }

    public static void capture(Observation.Context context, CaptureTarget target) {
        try {
            if (!(context instanceof LettuceObservationContext lettuce) || !lettuce.hasCommand()) return;
            RedisCommand<?, ?, ?> command = lettuce.getRequiredCommand();
            int maxChars = target.limits().maxValueChars();
            CommandArgs<?, ?> args = command.getArgs();
            String argsText = args == null ? "" : CaptureText.of(args.toCommandString(), maxChars);
            target.capture("command", command.getType() + (argsText.isEmpty() ? "" : " " + argsText));
            CommandOutput<?, ?, ?> output = command.getOutput();
            if (output == null) return;
            String error = output.getError();
            if (error != null) target.capture("error", error);
            else target.capture("result", CaptureText.of(output.get(), maxChars));
        } catch (RuntimeException | LinkageError ignored) {
            // 抓取失败不能影响 Redis 调用。
        }
    }
}
