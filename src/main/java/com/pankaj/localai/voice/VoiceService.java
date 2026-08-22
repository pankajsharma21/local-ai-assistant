package com.pankaj.localai.voice;

import com.pankaj.localai.config.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Voice is deliberately NOT another AI pipeline — it is a thin adapter around the same
 * AssistantService used by text chat:
 *
 *   mic audio -> whisper.cpp (local speech-to-text) -> AssistantService.chat(...) -> Piper (local
 *   text-to-speech) -> speaker
 *
 * Both whisper.cpp and Piper run as local command-line binaries (no network, no cloud STT/TTS API).
 * They are NOT bundled with this project because they are separate native tools with their own
 * models — run scripts/setup_voice.sh once, then set assistant.voice.enabled=true.
 *
 * If the binaries are missing, methods here fail with a clear message instead of a cryptic
 * ProcessBuilder stack trace, and VoiceController reports voice as disabled at /api/voice/status.
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final AssistantProperties.Voice config;

    public VoiceService(AssistantProperties props) {
        this.config = props.getVoice();
    }

    public boolean isAvailable() {
        return config.isEnabled()
                && Files.isExecutable(Path.of(config.getWhisperBinary()))
                && Files.exists(Path.of(config.getWhisperModel()))
                && Files.isExecutable(Path.of(config.getPiperBinary()))
                && Files.exists(Path.of(config.getPiperModel()));
    }

    public String statusMessage() {
        if (!config.isEnabled()) {
            return "Voice is disabled (assistant.voice.enabled=false). Run scripts/setup_voice.sh, then enable it.";
        }
        if (isAvailable()) {
            return "Voice is enabled and all binaries/models were found.";
        }
        return "Voice is enabled but whisper.cpp/Piper binaries or models are missing. " +
                "Run scripts/setup_voice.sh and check application.yml paths under assistant.voice.*";
    }

    /** Runs whisper.cpp on a WAV file and returns the transcribed text. */
    public String transcribe(Path wavFile) throws IOException, InterruptedException {
        requireAvailable();
        Path outPrefix = Files.createTempFile("whisper-out", "");
        Files.deleteIfExists(outPrefix); // whisper.cpp appends its own extension

        ProcessBuilder pb = new ProcessBuilder(
                config.getWhisperBinary(),
                "-m", config.getWhisperModel(),
                "-f", wavFile.toString(),
                "-otxt",
                "-of", outPrefix.toString(),
                "-nt", // no timestamps in output
                // whisper.cpp defaults to -l en, which would force English even on a multilingual
                // model. "auto" lets it detect the spoken language (handles Hindi/Hinglish and
                // gives Indian-accented English a better shot than the English-only models).
                "-l", config.getLanguage(),
                // whisper.cpp defaults to 4 threads regardless of machine size. Measured on a
                // 12-core box: 4 threads took 21s for an 11s clip, 8 threads took 12s. Leave a few
                // cores free so transcription doesn't starve the Ollama process running alongside.
                "-t", String.valueOf(resolveThreads())
        );
        pb.redirectErrorStream(true);
        addNativeLibraryPath(pb, config.getWhisperBinary());
        Process process = pb.start();
        String log = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("whisper.cpp timed out");
        }
        VoiceService.log.debug("whisper.cpp output:\n{}", log);

        Path txtFile = Path.of(outPrefix + ".txt");
        if (!Files.exists(txtFile)) {
            throw new IOException("whisper.cpp did not produce a transcript. Output:\n" + log);
        }
        String text = Files.readString(txtFile).strip();
        Files.deleteIfExists(txtFile);
        return text;
    }

    /** Runs Piper on the given text and returns the path to a generated WAV file. */
    public Path speak(String text) throws IOException, InterruptedException {
        requireAvailable();
        Path outWav = Files.createTempFile("piper-out", ".wav");

        ProcessBuilder pb = new ProcessBuilder(
                config.getPiperBinary(),
                "--model", config.getPiperModel(),
                "--output_file", outWav.toString()
        );
        pb.redirectErrorStream(true);
        addNativeLibraryPath(pb, config.getPiperBinary());
        Process process = pb.start();
        process.getOutputStream().write(text.getBytes());
        process.getOutputStream().close();

        String log = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Piper timed out");
        }
        VoiceService.log.debug("Piper output:\n{}", log);

        if (!Files.exists(outWav) || Files.size(outWav) == 0) {
            throw new IOException("Piper did not produce audio. Output:\n" + log);
        }
        return outWav;
    }

    /** Configured thread count, or an auto-size that leaves headroom for the LLM process. */
    private int resolveThreads() {
        if (config.getThreads() > 0) {
            return config.getThreads();
        }
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 4));
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(statusMessage());
        }
    }

    /**
     * Both whisper.cpp and Piper ship as prebuilt binary releases with their shared libraries
     * (libggml*.so, libonnxruntime.so, etc.) sitting right next to the executable rather than
     * installed system-wide — there's no root access here to put them on the system library path.
     * Point LD_LIBRARY_PATH at the binary's own directory so the dynamic linker finds them.
     */
    private void addNativeLibraryPath(ProcessBuilder pb, String binaryPath) {
        Path parentDir = Path.of(binaryPath).toAbsolutePath().getParent();
        if (parentDir == null) {
            return;
        }
        String existing = System.getenv("LD_LIBRARY_PATH");
        String updated = existing == null || existing.isBlank() ? parentDir.toString() : parentDir + ":" + existing;
        pb.environment().put("LD_LIBRARY_PATH", updated);
    }
}
