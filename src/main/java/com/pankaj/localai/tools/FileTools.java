package com.pankaj.localai.tools;

import com.pankaj.localai.config.AssistantProperties;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lets the agent read the real file/directory contents on disk — the piece that turns
 * "search_code found a snippet" into "read_file gives the full source" for the code-assistant use
 * case. Sandboxed to assistant.files.allowed-root so the model can never be tricked (directly or
 * via prompt injection in a retrieved chunk) into reading files outside the project, e.g. via
 * "../../../etc/passwd".
 */
@Component
public class FileTools {

    private final Path allowedRoot;

    public FileTools(AssistantProperties props) {
        this.allowedRoot = Path.of(props.getFiles().getAllowedRoot()).toAbsolutePath().normalize();
    }

    @Tool("""
        Read the full contents of a text file, given a path relative to the project root.
        Use this after searchCode/searchDocs points you at a specific file and you need more context
        than the snippet gave you. Returns an error message if the file does not exist or is outside
        the allowed project directory.
        """)
    public String readFile(String relativePath) {
        try {
            Path resolved = resolveSafely(relativePath);
            if (!Files.isRegularFile(resolved)) {
                return "Error: '" + relativePath + "' is not a readable file.";
            }
            long size = Files.size(resolved);
            if (size > 200_000) {
                return "Error: file is too large to read in full (" + size + " bytes). Ask for a narrower path.";
            }
            return Files.readString(resolved);
        } catch (SecurityException e) {
            return "Error: access to '" + relativePath + "' is outside the allowed project directory.";
        } catch (IOException e) {
            return "Error reading '" + relativePath + "': " + e.getMessage();
        }
    }

    @Tool("""
        List files and subdirectories inside a directory, given a path relative to the project root
        (use "." for the project root itself). Use this to explore the project structure before
        deciding which file to read or search.
        """)
    public String listFiles(String relativeDir) {
        try {
            Path resolved = resolveSafely(relativeDir);
            if (!Files.isDirectory(resolved)) {
                return "Error: '" + relativeDir + "' is not a directory.";
            }
            try (Stream<Path> entries = Files.list(resolved)) {
                String listing = entries
                        .map(p -> (Files.isDirectory(p) ? "[dir]  " : "[file] ") + allowedRoot.relativize(p))
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return listing.isBlank() ? "(empty directory)" : listing;
            }
        } catch (SecurityException e) {
            return "Error: access to '" + relativeDir + "' is outside the allowed project directory.";
        } catch (IOException e) {
            return "Error listing '" + relativeDir + "': " + e.getMessage();
        }
    }

    private Path resolveSafely(String relativePath) {
        Path resolved = allowedRoot.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(allowedRoot)) {
            throw new SecurityException("Path escapes allowed root: " + relativePath);
        }
        return resolved;
    }
}
