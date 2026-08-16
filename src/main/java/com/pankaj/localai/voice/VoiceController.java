package com.pankaj.localai.voice;

import com.pankaj.localai.assistant.AssistantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Voice endpoints. All three are thin wrappers: they never talk to the LLM/tools directly, they
 * just convert audio<->text and hand off to the same AssistantService that text chat uses — proving
 * the "one brain, different I/O" architecture end to end.
 *
 * Requires scripts/setup_voice.sh to have been run and assistant.voice.enabled=true; otherwise
 * every endpoint here returns a clear 503 with setup instructions instead of a stack trace.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    private final VoiceService voiceService;
    private final AssistantService assistantService;

    public VoiceController(VoiceService voiceService, AssistantService assistantService) {
        this.voiceService = voiceService;
        this.assistantService = assistantService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("available", voiceService.isAvailable(), "message", voiceService.statusMessage());
    }

    /** Audio in -> transcript out. Expects a 16kHz mono WAV file (what whisper.cpp expects). */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(@RequestParam("audio") MultipartFile audio) {
        if (!voiceService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of("error", voiceService.statusMessage()));
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("voice-in", ".wav");
            audio.transferTo(tmp);
            String text = voiceService.transcribe(tmp);
            return ResponseEntity.ok(Map.of("text", text));
        } catch (Exception e) {
            log.error("Transcription failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Text in -> spoken WAV out. */
    @PostMapping(value = "/speak", produces = "audio/wav")
    public ResponseEntity<?> speak(@RequestBody Map<String, String> body) {
        if (!voiceService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of("error", voiceService.statusMessage()));
        }
        try {
            Path wav = voiceService.speak(body.getOrDefault("text", ""));
            Resource resource = new FileSystemResource(wav.toFile());
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(resource);
        } catch (Exception e) {
            log.error("Speech synthesis failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Full loop: audio in -> transcribe -> ask the assistant -> speak the reply -> audio out. */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "audio/wav")
    public ResponseEntity<?> voiceChat(@RequestParam("audio") MultipartFile audio,
                                       @RequestParam("sessionId") String sessionId) {
        if (!voiceService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of("error", voiceService.statusMessage()));
        }
        Path tmpIn = null;
        try {
            tmpIn = Files.createTempFile("voice-in", ".wav");
            audio.transferTo(tmpIn);
            String question = voiceService.transcribe(tmpIn);
            String reply = assistantService.chat(sessionId, question);
            Path wavOut = voiceService.speak(reply);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .header("X-Transcript", encodeHeaderSafe(question))
                    .header("X-Reply", encodeHeaderSafe(reply))
                    .body(new FileSystemResource(wavOut.toFile()));
        } catch (Exception e) {
            log.error("Voice chat failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            deleteQuietly(tmpIn);
        }
    }

    private String encodeHeaderSafe(String value) {
        // HTTP headers must be ISO-8859-1/ASCII-ish; strip newlines and non-ASCII to stay safe.
        return value.replaceAll("[\\r\\n]", " ").replaceAll("[^\\x00-\\x7F]", "");
    }

    private void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }
}
