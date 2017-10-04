import java.util.*;

class ProcessedGame {

    private String gameName;

    private String pendingMergeFolder;

    private String pendingProcessFolder;

    private List<String> pendingMergeVidsInfos = new ArrayList<>();

    private Map<String, ProcessedVideo> processedVideos = new LinkedHashMap<>();

    ProcessedGame(String gameName) {
        this.gameName = gameName;
    }

    void setPendingMergeFolder(String pendingMergeFolder) {
        this.pendingMergeFolder = pendingMergeFolder;
    }

    void setPendingProcessFolder(String pendingProcessFolder) {
        this.pendingProcessFolder = pendingProcessFolder;
    }

    void addProcessedVideo(String vidPath, ProcessedVideo processedVideo) {
        processedVideos.put(vidPath, processedVideo);
    }

    void addPendingMergeVidPath(String vidPath) {
        pendingMergeVidsInfos.add(vidPath);
    }

    String gameName() {
        return gameName;
    }

    @SuppressWarnings("unused")
    String pendingMergeFolder() {
        return pendingMergeFolder;
    }

    String pendingProcessFolder() {
        return pendingProcessFolder;
    }

    List<String> pendingMergeVidsInfos() {
        return pendingMergeVidsInfos;
    }

    Map<String, ProcessedVideo> processedVideos() {
        return processedVideos;
    }
}
