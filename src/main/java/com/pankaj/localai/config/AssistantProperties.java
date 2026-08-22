package com.pankaj.localai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding of the "assistant.*" section of application.yml.
 */
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {

    private Ollama ollama = new Ollama();
    private Rag rag = new Rag();
    private Files files = new Files();
    private Voice voice = new Voice();
    private WebSearch webSearch = new WebSearch();

    public Ollama getOllama() { return ollama; }
    public void setOllama(Ollama ollama) { this.ollama = ollama; }
    public Rag getRag() { return rag; }
    public void setRag(Rag rag) { this.rag = rag; }
    public Files getFiles() { return files; }
    public void setFiles(Files files) { this.files = files; }
    public Voice getVoice() { return voice; }
    public void setVoice(Voice voice) { this.voice = voice; }
    public WebSearch getWebSearch() { return webSearch; }
    public void setWebSearch(WebSearch webSearch) { this.webSearch = webSearch; }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "llama3.2";
        private double temperature = 0.3;
        private int timeoutSeconds = 300;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Rag {
        private String docsPath = "./data/docs";
        private String codePath = "./data/code";
        private String storePath = "./data/store";
        private int chunkSize = 500;
        private int chunkOverlap = 50;
        private int maxResults = 5;
        private double minScore = 0.6;

        public String getDocsPath() { return docsPath; }
        public void setDocsPath(String docsPath) { this.docsPath = docsPath; }
        public String getCodePath() { return codePath; }
        public void setCodePath(String codePath) { this.codePath = codePath; }
        public String getStorePath() { return storePath; }
        public void setStorePath(String storePath) { this.storePath = storePath; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    public static class Files {
        private String allowedRoot = ".";

        public String getAllowedRoot() { return allowedRoot; }
        public void setAllowedRoot(String allowedRoot) { this.allowedRoot = allowedRoot; }
    }

    public static class WebSearch {
        private boolean enabled = false;
        private String tavilyApiKey = "";
        private int maxResults = 5;
        private String duckduckgoPython = "./tools/websearch-venv/bin/python";
        private String duckduckgoScript = "./scripts/ddg_search.py";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTavilyApiKey() { return tavilyApiKey; }
        public void setTavilyApiKey(String tavilyApiKey) { this.tavilyApiKey = tavilyApiKey; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public String getDuckduckgoPython() { return duckduckgoPython; }
        public void setDuckduckgoPython(String duckduckgoPython) { this.duckduckgoPython = duckduckgoPython; }
        public String getDuckduckgoScript() { return duckduckgoScript; }
        public void setDuckduckgoScript(String duckduckgoScript) { this.duckduckgoScript = duckduckgoScript; }
    }

    public static class Voice {
        private boolean enabled = false;
        private String whisperBinary = "./tools/whisper/main";
        private String whisperModel = "./tools/whisper/models/ggml-base.en.bin";
        private String piperBinary = "./tools/piper/piper";
        private String piperModel = "./tools/piper/voices/en_US-lessac-medium.onnx";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getWhisperBinary() { return whisperBinary; }
        public void setWhisperBinary(String whisperBinary) { this.whisperBinary = whisperBinary; }
        public String getWhisperModel() { return whisperModel; }
        public void setWhisperModel(String whisperModel) { this.whisperModel = whisperModel; }
        public String getPiperBinary() { return piperBinary; }
        public void setPiperBinary(String piperBinary) { this.piperBinary = piperBinary; }
        public String getPiperModel() { return piperModel; }
        public void setPiperModel(String piperModel) { this.piperModel = piperModel; }
    }
}
