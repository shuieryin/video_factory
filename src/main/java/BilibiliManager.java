import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class BilibiliManager {

    private static final String OUTPUT_FORMAT = "flv";
    @SuppressWarnings("FieldCanBeLocal")
    private static int OVERLAP_DURATION_SECONDS = 1;
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private static final String vidPathPatternStr = "^(.*)/(([^/]+)\\s(\\d{4})\\.(\\d{2})\\.(\\d{2})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)";
    private static Pattern vidPathPattern = Pattern.compile(vidPathPatternStr + "$");
    private static Pattern processedVidPathPattern = Pattern.compile(vidPathPatternStr + "\\.done\\.(\\d+)$");
    private static final long LIMIT_SIZE_BYTES = (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20);
    private static Pattern filesizePattern = Pattern.compile("(\\d+)");
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static final String replaceSpace = "\\s";

//    private static final int WIDTH_SIZE = 1080;
    private static final int CRF = 5;
    private static final int AUDIO_BIT_RATE = 190;
    private static final int BIT_RATE = 8000;
    // private static final int FPS = 50;

    private Map<String, ProcessedGame> processedGames = new LinkedHashMap<>();

    BilibiliManager() throws InterruptedException, IOException {
        initProcessVideo();
    }

    void mergeVideos() {
        try {
            System.out.println("merging videos...");
            List<String> pendingMergePaths = pendingProcessVids("/srv/grand_backup/samba/vids/pending_merge", false);
            Map<String, List<String>> pendingMergeVidsInfos = new HashMap<>();
            for (String vidPath : pendingMergePaths) {
                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("vidPath not found: " + vidPath);
                    continue;
                }
                String gameName = vidPathMatcher.group(3);
                List<String> vidsPaths = pendingMergeVidsInfos.computeIfAbsent(gameName, k -> new ArrayList<>());
                vidsPaths.add(vidPath);
            }

            for (Map.Entry<String, List<String>> entry : pendingMergeVidsInfos.entrySet()) {
                String afterConcatName = "";
                List<String> vidsPath = entry.getValue();
                String processFilePath;
                String parsed_pending_process_folder;
                String parsedProcessFilePath = "";
                for (int i = 0; i < vidsPath.size(); i++) {
                    String vidPath = vidsPath.get(i).replaceAll("'", "\\\\'");
                    System.out.println("vidPath: " + vidPath);
                    if (i == 0) {
                        afterConcatName = vidPath;
                        Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                        if (!vidPathMatcher.find()) {
                            System.out.println("vidPath not found: " + vidPath);
                            continue;
                        }
                        String gameFolder = vidPathMatcher.group(1);
                        String gameName = vidPathMatcher.group(3);
                        String pending_process_folder = gameFolder.replaceFirst("pending_merge", "pending_process");
                        processFilePath = pending_process_folder + "/" + gameName + ".txt";
                        parsedProcessFilePath = processFilePath.replaceAll(replaceSpace, "\\\\ ");
                        System.out.println("parsedProcessFilePath: " + parsedProcessFilePath);
                        parsed_pending_process_folder = pending_process_folder.replaceAll(replaceSpace, "\\\\ ");
                        ManageServer.executeCommand("rm -rf " + parsed_pending_process_folder);
                        ManageServer.executeCommand("mkdir -p " + parsed_pending_process_folder);
                    }
                    ManageServer.executeCommand("echo \"file " + vidPath.replaceAll(replaceSpace, "\\\\ ") + "\" | tee -a " + parsedProcessFilePath);
                    ManageServer.executeCommand("echo $'\\r' >> " + parsedProcessFilePath);
                }
                String finalOutputName = afterConcatName.replaceAll(replaceSpace, "\\\\ ").replaceFirst("pending_merge", "pending_process");
                String concatVidsCommand = "ffmpeg -f concat -safe 0 -i " + parsedProcessFilePath + " -c copy " + finalOutputName;
                ManageServer.executeCommandRemotely(concatVidsCommand, true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void processVideos() {
        System.out.println();
        System.out.println("processing videos...");
        try {
            List<String> pendingProcessPaths = pendingProcessVids("/srv/grand_backup/samba/vids/pending_process", false);
            System.out.println(pendingProcessPaths);
            for (String vidPath : pendingProcessPaths) {
                System.out.println("vidPath: " + vidPath);
                String parsedVidPath = vidPath.replaceAll(replaceSpace, "\\\\ ").replaceAll("'", "\\\\'");
                long totalSeconds = videoDuration(parsedVidPath);
                if (0 == totalSeconds) {
                    continue;
                }

                long clipCount = 0;

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("vidPath not found: " + vidPath);
                    continue;
                }
                String pending_process_folder = vidPathMatcher.group(1).replaceAll(replaceSpace, "\\\\ ").replaceAll("'", "\\\\'");
                String pending_merge_folder = pending_process_folder.replaceFirst("pending_process", "pending_merge");
                String gameName = vidPathMatcher.group(3);

                ProcessedGame processedGame = processedGames.get(gameName);
                if (null == processedGame) {
                    processedGame = new ProcessedGame(gameName);
                    processedGames.put(gameName, processedGame);
                }

                Map<String, ProcessedVideo> processedVideos = processedGame.processedVideos();
                ProcessedVideo processedVideo = processedVideos.get(vidPath);
                if (null == processedVideo) {
                    processedVideo = new ProcessedVideo(vidPath, vidPathPattern);
                    processedVideos.put(vidPath, processedVideo);
                    processedGame.addProcessedVideo(vidPath, processedVideo);
                }

                long startPos = 0;
                String lastProcessedClipPath;
                do {
                    lastProcessedClipPath = processedVideo.processedPath + processedVideo.uuid() + "-" + (++clipCount) + "." + OUTPUT_FORMAT;
                    String command = "ffmpeg -y -i " + parsedVidPath
                            + " -ss " + startPos
                            + " -threads 0 "
                            + " -vsync 0 "
//                            + " -r " + FPS
                            + " -b " + BIT_RATE + "k"
                            + " -minrate " + BIT_RATE + "k"
                            + " -maxrate " + BIT_RATE + "k"
                            + " -bufsize " + BIT_RATE + "k"
                            + " -c:a aac -strict -2 -b:a " + AUDIO_BIT_RATE + "k"
//                            + " -vf scale=w=-1:h=" + WIDTH_SIZE + ":force_original_aspect_ratio=decrease"
                            + " -codec:v libx264"
                            + " -crf " + CRF
                            + " -fs " + LIMIT_SIZE_BYTES
                            + " " + lastProcessedClipPath;

                    ManageServer.executeCommandRemotely(command, true);

                    System.out.println();
                    System.out.println("startPos: " + startPos);
                    System.out.println("totalSeconds: " + totalSeconds);
                    System.out.println();

                    startPos += videoDuration(lastProcessedClipPath) - OVERLAP_DURATION_SECONDS;
                    String rawFileSize = ManageServer.executeCommand("ls -l " + lastProcessedClipPath + " | grep -oP \"^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\K(\\S+)\" | tr -d '\\n'");
                    Matcher fileSizeMatcher = filesizePattern.matcher(rawFileSize);
                    fileSizeMatcher.find();
                    System.out.println("startPos: " + startPos);
                } while (startPos < totalSeconds - OVERLAP_DURATION_SECONDS);

                System.out.println();
                System.out.println("startPos: " + startPos);
                System.out.println("totalSeconds: " + totalSeconds);
                System.out.println();

                ManageServer.executeCommand("rm -f " + parsedVidPath + "; rm -rf " + pending_process_folder + "; rm -rf " + pending_merge_folder);

                System.out.println(processedVideo.gameName() + " total vidSeconds: " + totalSeconds + ", and chopped into " + clipCount + " part(s).");

                processedGames.remove(processedGame.gameName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("process videos done");
        System.out.println();

        System.exit(0);
    }

    private List<String> pendingProcessVids(String path, boolean isGetDones) throws IOException {
        List<String> vidsList = new ArrayList<>();

        Path rootPath = Paths.get(path);

        if (!Files.exists(rootPath)) {
            return vidsList;
        }

        Stream<Path> gamePaths = Files.walk(rootPath);
        gamePaths.forEach(gamePath -> {
            if (Files.isDirectory(gamePath) && !gamePath.toString().equals(rootPath.toString())) {
                try (Stream<Path> vidsPath = Files.walk(gamePath)) {
                    vidsPath.forEach(vidPath -> {
                        if (Files.isDirectory(vidPath)) {
                            return;
                        }

                        String vidPathStr = vidPath.toString();
                        Matcher matcher = isGetDones ? processedVidPattern.matcher(vidPathStr) : vidPathPattern.matcher(vidPathStr);
                        if (matcher.find()) {
                            vidsList.add(vidPathStr);
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        Collections.sort(vidsList);

        return vidsList;
    }

    private long videoDuration(String vidPath) {
        String timeOutput = ManageServer.executeCommand("ffmpeg -i " + vidPath + " 2>&1 | grep Duration | grep -oP \"^\\s*Duration:\\s*\\K(\\S+),\" | cut -c 1-11");

        Matcher timerMatcher = timePattern.matcher(timeOutput);
        if (!timerMatcher.find()) {
            return 0;
        }
        int vidHour = Integer.parseInt(timerMatcher.group(1));
        int vidMinutes = Integer.parseInt(timerMatcher.group(2));
        int vidSeconds = Integer.parseInt(timerMatcher.group(3));
        return 1 + vidSeconds + 60 * vidMinutes + 60 * 60 * vidHour;
    }

    private void initProcessVideo() throws IOException {
        System.out.println();
        System.out.println("initProcessVideo start");
        List<String> pendingUploadVids = pendingProcessVids("/srv/grand_backup/samba/vids/pending_merge", true);

        for (String pendingUploadVid : pendingUploadVids) {
            System.out.println("init pending upload vid: " + pendingUploadVid);
            Matcher vidPathMatcher = processedVidPathPattern.matcher(pendingUploadVid);
            if (!vidPathMatcher.find()) {
                continue;
            }

            String gameName = vidPathMatcher.group(3);
            ProcessedGame processedGame = processedGames.get(gameName);
            if (null == processedGame) {
                processedGame = new ProcessedGame(gameName);
                processedGames.put(gameName, processedGame);
            }

            ProcessedVideo processedVideo = new ProcessedVideo(pendingUploadVid, processedVidPathPattern);
            processedGame.addProcessedVideo(pendingUploadVid, processedVideo);
        }
        System.out.println();
    }

    String handleUserInput(Map<String, List<String>> getParams) {
        String returnContent = "";
        try {
            String uid = getParams.get("uid").get(0);

            JSONObject returnJSON = new JSONObject();
            returnJSON.put("uid", uid);
            String event = getParams.get("event").get(0);
            switch (event) {
                case "pending_process_vids":
                    returnJSON.put("vids_list", pendingProcessVids("/srv/grand_backup/samba/vids/pending_process", false));
                    break;
                case "pending_upload_vids":
                    returnJSON.put("vids_list", pendingProcessVids("/srv/grand_backup/samba/vids/pending_process", true));
                    break;
            }

            returnJSON.toMap().forEach((key, value) -> System.out.println(key + "=" + value));
            System.out.println();

            returnContent = returnJSON.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return returnContent;
    }
}