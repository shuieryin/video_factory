import java.util.LinkedHashMap;
import java.util.Map;

class ProcessedGame {

    private String gameName;

    private Map<String, ProcessedVideo> processedVideos = new LinkedHashMap<>();

    ProcessedGame(String gameName) {
        this.gameName = gameName;
    }

    void addProcessedVideo(String vidPath, ProcessedVideo processedVideo) {
        processedVideos.put(vidPath, processedVideo);
    }

    String gameName() {
        return gameName;
    }

    Map<String, ProcessedVideo> processedVideos() {
        return processedVideos;
    }
}
