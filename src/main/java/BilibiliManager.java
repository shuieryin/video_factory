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

    private static final String OUTPUT_FORMAT = "mp4";
    @SuppressWarnings("FieldCanBeLocal")
    private static int OVERLAP_DURATION_SECONDS = 1;
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private static final String vidPathPatternStr = "^(.*)/(([^/]+)\\s(\\d{2})\\.(\\d{2})\\.(\\d{4})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)";
    private static Pattern vidPathPattern = Pattern.compile(vidPathPatternStr + "$");
    private static Pattern processedVidPathPattern = Pattern.compile(vidPathPatternStr + "\\.done\\.(\\d+)$");
    private static final long LIMIT_SIZE_BYTES = (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20);
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static final String PENDING_MERGE_PATH = "/srv/grand_backup/samba/vids/pending_merge";
    private static final String MERGED_PATH = "/srv/grand_backup/samba/vids/merged";
    private static final String PENDING_PROCESS_PATH = "/srv/grand_backup/samba/vids/pending_process";

    //    private static final int WIDTH_SIZE = 1080;
//    private static final int CRF = 5;
    private static final int AUDIO_BIT_RATE = 192;
    private static final int BIT_RATE = 10000;
    private static final int FPS = 50;
    private static final int CHOP_PER_COUNT = 4;

    private Map<String, ProcessedGame> processedGames = new LinkedHashMap<>();

    BilibiliManager() throws InterruptedException, IOException {
        initProcessVideo();
    }

    Map<String, ProcessedGame> mergeVideos() {
        try {
            processedGames = new LinkedHashMap<>();
            System.out.println("=======merging videos...");
            List<String> pendingMergePaths = pendingProcessVids(PENDING_MERGE_PATH, false);
            if (pendingMergePaths.isEmpty()) {
                return processedGames;
            }

            for (String vidPath : pendingMergePaths) {
                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("=======vidPath not found: " + vidPath);
                    continue;
                }
                String gameName = vidPathMatcher.group(3);
                ProcessedGame processedGame = processedGames.get(gameName);
                if (null == processedGame) {
                    processedGame = new ProcessedGame(gameName);
                    processedGames.put(gameName, processedGame);
                }
                processedGame.addPendingMergeVidPath(vidPath);
            }

            for (Map.Entry<String, ProcessedGame> entry : processedGames.entrySet()) {
                ProcessedGame processedGame = entry.getValue();
                List<String> vidsPath = processedGame.pendingMergeVidsInfos();
                if (vidsPath.isEmpty()) {
                    continue;
                }

                String processFilePath, pendingMergeFolder = "", parsedProcessFilePath = "", pendingProcessFolder, afterConcatName = "";
                for (int i = 0; i < vidsPath.size(); i++) {
                    String oriPath = vidsPath.get(i);
                    String vidPath = Common.strParse(oriPath);
                    System.out.println("=======vidPath: " + vidPath);
                    if (i == 0) {
                        afterConcatName = vidPath;
                        Matcher vidPathMatcher = vidPathPattern.matcher(oriPath);
                        if (!vidPathMatcher.find()) {
                            System.out.println("=======vidPath not found: " + oriPath);
                            continue;
                        }
                        pendingMergeFolder = Common.strParse(vidPathMatcher.group(1));
                        processedGame.setPendingMergeFolder(pendingMergeFolder);
                        System.out.println("=======pendingMergeFolder: " + pendingMergeFolder);

                        String gameName = Common.strParse(vidPathMatcher.group(3));

                        pendingProcessFolder = pendingMergeFolder.replaceFirst("pending_merge", "pending_process");
                        processedGame.setPendingProcessFolder(pendingProcessFolder);
                        System.out.println("=======pendingProcessFolder: " + pendingProcessFolder);

                        processFilePath = pendingProcessFolder + "/" + gameName + ".txt";
                        parsedProcessFilePath = processFilePath;
                        System.out.println("=======parsedProcessFilePath: " + parsedProcessFilePath);
                        ManageServer.executeCommand("rm -rf " + pendingProcessFolder);
                        ManageServer.executeCommand("mkdir -p " + pendingProcessFolder);
                    }

                    ManageServer.executeCommand("echo \"file " + vidPath + "\" | tee -a " + parsedProcessFilePath);
                    ManageServer.executeCommand("echo $'\\r' >> " + parsedProcessFilePath);
                }
                if (vidsPath.size() == 1) {
                    ManageServer.executeCommand("cp " + pendingMergeFolder + " " + PENDING_PROCESS_PATH);
                } else {
                    String finalOutputName = afterConcatName.replaceFirst("pending_merge", "pending_process");
                    String concatVidsCommand = "ffmpeg -f concat -safe 0 -i " + parsedProcessFilePath + " -vcodec copy -acodec copy " + finalOutputName;
                    ManageServer.executeCommandRemotely(concatVidsCommand, true);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            processedGames = new LinkedHashMap<>();
        }

        return processedGames;
    }

    void processVideos() {
        System.out.println();
        System.out.println("=======processing videos...");
        try {
            List<String> pendingProcessPaths = pendingProcessVids(PENDING_PROCESS_PATH, false);
            System.out.println(pendingProcessPaths);
            for (String vidPath : pendingProcessPaths) {
                System.out.println("=======vidPath: " + vidPath);
                String parsedVidPath = Common.strParse(vidPath);
                long totalDuration = videoDuration(parsedVidPath);
                if (0 == totalDuration) {
                    continue;
                }

                long clipCount = 0;

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                if (!vidPathMatcher.find()) {
                    System.out.println("=======vidPath not found: " + vidPath);
                    continue;
                }
                //String pending_merge_folder = pending_process_folder.replaceFirst("pending_process", "pending_merge");
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

                long startPos = 0, lastStartPos = 0;
                int chopCount = 1;
                String lastProcessedClipPath, lastParsedVidPath = parsedVidPath;
                do {
                    if (clipCount % CHOP_PER_COUNT == 0 && clipCount > 0) {
                        String tempPath = processedGame.pendingProcessFolder() + "/temp_" + chopCount + "." + OUTPUT_FORMAT;
                        String chopCommand = "ffmpeg -y -i " + lastParsedVidPath
                                + " -ss " + lastStartPos
                                + " -vcodec copy "
                                + tempPath;
                        lastStartPos = 0;
                        System.out.println();
                        System.out.println("=========target pending process VidPath: " + tempPath);
                        System.out.println();
                        ManageServer.executeCommandRemotely(chopCommand, true);
                        ManageServer.executeCommand("rm -f " + lastParsedVidPath);
                        lastParsedVidPath = tempPath;
                        chopCount++;
                    }

                    lastProcessedClipPath = processedVideo.processedPath + processedVideo.uuid() + "-" + (++clipCount) + "." + OUTPUT_FORMAT;
                    String command = "ffmpeg -y -i " + lastParsedVidPath
                            + " -ss " + lastStartPos
                            + " -threads 0"
                            + " -vsync 1" // fix non-monotonic pts warning
                            + " -r " + FPS // fix past duration too large warning
                            + " -b:v " + BIT_RATE + "k"
                            + " -minrate " + BIT_RATE + "k"
                            + " -maxrate " + BIT_RATE + "k"
                            + " -bufsize " + BIT_RATE + "k"
                            + " -acodec aac -strict -2 -sample_rate 44100 -b:a " + AUDIO_BIT_RATE + "k"
//                            + " -vf scale=w=-1:h=" + WIDTH_SIZE + ":force_original_aspect_ratio=decrease"
                            + " -vcodec libx264"
//                            + " -crf " + CRF
                            + " -fs " + LIMIT_SIZE_BYTES
                            + " " + lastProcessedClipPath;

                    ManageServer.executeCommandRemotely(command, true);

                    long lastClipDuration = videoDuration(lastProcessedClipPath);
                    long remainDuration = totalDuration - startPos - lastClipDuration;

                    lastStartPos += lastClipDuration - OVERLAP_DURATION_SECONDS;
                    startPos += lastClipDuration - OVERLAP_DURATION_SECONDS;

                    System.out.println();
                    System.out.println("=========lastClipDuration: " + lastClipDuration);
                    System.out.println("=========ClipStartPos: " + startPos);
                    if (remainDuration > 0) {
                        System.out.println("=========target clipCount: " + (clipCount + 1));
                    }
                    System.out.println("=========remainDuration: " + remainDuration);
                    System.out.println("=========totalDuration: " + totalDuration);
                    System.out.println();
                } while (startPos < totalDuration - 2);

                StringBuilder removePendingMergeVidsInfosBuilder = new StringBuilder();
                removePendingMergeVidsInfosBuilder.append("mkdir -p ");
                removePendingMergeVidsInfosBuilder.append(MERGED_PATH);
                removePendingMergeVidsInfosBuilder.append("; ");
                for (String pendingMergeVidPath : processedGame.pendingMergeVidsInfos()) {
                    removePendingMergeVidsInfosBuilder.append("mv ");
                    removePendingMergeVidsInfosBuilder.append(Common.strParse(pendingMergeVidPath));
                    removePendingMergeVidsInfosBuilder.append(" ");
                    removePendingMergeVidsInfosBuilder.append(MERGED_PATH);
                    removePendingMergeVidsInfosBuilder.append(Common.strParse(gameName));
                    removePendingMergeVidsInfosBuilder.append("/; ");
                }

                removePendingMergeVidsInfosBuilder.append("rm -rf ");
                removePendingMergeVidsInfosBuilder.append(processedGame.pendingProcessFolder());
                ManageServer.executeCommand(removePendingMergeVidsInfosBuilder.toString());

                System.out.println("=======" + processedVideo.gameName() + " total vidDuration: " + totalDuration + ", and chopped into " + clipCount + " part(s).");

                processedGames.remove(processedGame.gameName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("=======process videos done");
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

        int result = vidSeconds + 60 * vidMinutes + 60 * 60 * vidHour;
        return result <= 0 ? 0 : result + 1;
    }

    private void initProcessVideo() throws IOException {
        System.out.println();
        System.out.println("initProcessVideo start");
        List<String> pendingUploadVids = pendingProcessVids(PENDING_MERGE_PATH, true);

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
                    returnJSON.put("vids_list", pendingProcessVids(PENDING_PROCESS_PATH, false));
                    break;
                case "pending_upload_vids":
                    returnJSON.put("vids_list", pendingProcessVids(PENDING_PROCESS_PATH, true));
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