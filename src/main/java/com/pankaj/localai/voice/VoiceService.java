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
                "-nt" // no timestamps in output
        );
        pb.redirectErrorStream(true);
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

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(statusMessage());
        }
    }
}
