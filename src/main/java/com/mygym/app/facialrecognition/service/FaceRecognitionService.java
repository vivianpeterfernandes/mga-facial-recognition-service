package com.mygym.app.facialrecognition.service;

import static com.mygym.app.facialrecognition.util.FacialConstants.MEMBER_GET_BY_MEMBERID_ENDPOINT;
import static com.mygym.app.facialrecognition.util.FacialConstants.MEMBER_GET_BY_USERNAME_ENDPOINT;
import static com.mygym.app.facialrecognition.util.FacialConstants.MEMBER_REST_CLIENT_BEAN;
import static com.mygym.app.facialrecognition.util.FacialConstants.MEMBER_UPDATE_FACE_REGIS_ENDPOINT;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.mygym.app.facialrecognition.model.FaceProfile;
import com.mygym.app.facialrecognition.model.Member;
import com.mygym.app.facialrecognition.model.RecognitionResult;
import com.mygym.app.facialrecognition.repository.FaceProfileRepository;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class FaceRecognitionService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(FaceRecognitionService.class);
	
	private RestClient restClient;

    private ZooModel<Image, float[]> recognitionModel;
    private Predictor<Image, float[]> faceRecognizer;
    private Net dnnFaceDetector;
    
    private final FaceProfileRepository profileRepository;
    
    public FaceRecognitionService(@Qualifier(MEMBER_REST_CLIENT_BEAN) RestClient restClient, FaceProfileRepository profileRepository) {
		this.restClient = restClient;
    	this.profileRepository = profileRepository;
    }
    
    @PostConstruct
    public void init() throws Exception {
        LOGGER.info("[BOOT-AI] Loading native OpenCV library layers...");
        nu.pattern.OpenCV.loadLocally();

        // Create a single secured workspace directory inside the container's physical temp drive
        Path tempModelDir = Files.createTempDirectory("mga_compiled_models");
        
        // Target physical path allocations for all three neural structures
        Path tempProto = tempModelDir.resolve("deploy.prototxt");
        Path tempModel = tempModelDir.resolve("res10_300x300_ssd_iter_140000.caffemodel");
        Path targetModelFile = tempModelDir.resolve("facenet.pt");

        LOGGER.info("[BOOT-AI] Extracting nested resource files down to physical sandbox file tracks...");
        
        // 1. Extract Face Detector Prototxt Schema definition
        try (InputStream in = new ClassPathResource("deploy.prototxt").getInputStream()) { 
            Files.copy(in, tempProto, StandardCopyOption.REPLACE_EXISTING); 
        }
        
        // 2. Extract Caffe Model Weight configurations
        try (InputStream in = new ClassPathResource("res10_300x300_ssd_iter_140000.caffemodel").getInputStream()) { 
            Files.copy(in, tempModel, StandardCopyOption.REPLACE_EXISTING); 
        }
        
        // 3. Extract FaceNet PyTorch matrix profiles
        try (InputStream in = new ClassPathResource("facenet.pt").getInputStream()) {
            Files.copy(in, targetModelFile, StandardCopyOption.REPLACE_EXISTING);
        }

        LOGGER.info("[BOOT-AI] Initializing OpenCV DNN Caffe framework architecture...");
        this.dnnFaceDetector = Dnn.readNetFromCaffe(
            tempProto.toAbsolutePath().toString(), 
            tempModel.toAbsolutePath().toString()
        );

        LOGGER.info("[BOOT-AI] Constructing DJL Criteria map arrays targeting FaceNet engine configurations...");
        Criteria<Image, float[]> recCriteria = Criteria.builder()
                .setTypes(Image.class, float[].class)
                .optEngine("PyTorch")
                .optModelPath(tempModelDir) // Feeds the absolute local directory path directly
                .optModelName("facenet")
                .optTranslator(new FaceNetTranslator())
                .build();
                
        this.recognitionModel = recCriteria.loadModel();
        this.faceRecognizer = recognitionModel.newPredictor();
        
        LOGGER.info("[BOOT-AI] Neural network deployment clusters linked successfully. Service operational.");
    }

    public float[] extractEmbeddingsFromFace(MultipartFile file) throws Exception {
        String uniqueDir = System.getProperty("java.io.tmpdir");
        Path uniqueTempPath = Paths.get(uniqueDir, "face_upload_" + UUID.randomUUID().toString() + ".jpg");
        	
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, uniqueTempPath, StandardCopyOption.REPLACE_EXISTING);
        }
        
        Mat imageMat = Imgcodecs.imread(uniqueTempPath.toAbsolutePath().toString());
        Files.deleteIfExists(uniqueTempPath);
        
        if (imageMat.empty()) {
            throw new IllegalArgumentException("Failed to decode image file matrix.");
        }

        Mat blob = Dnn.blobFromImage(imageMat, 1.0, new Size(300, 300), new Scalar(104.0, 177.0, 123.0), false, false);
        dnnFaceDetector.setInput(blob);
        Mat detections = dnnFaceDetector.forward();
        blob.release();

        Mat detectionMat = detections.reshape(1, detections.size(2));
        detections.release();

        int frameWidth = imageMat.cols();
        int frameHeight = imageMat.rows();
        Rect bestFaceRect = null;
        float maxConfidence = 0.0f;

        for (int i = 0; i < detectionMat.rows(); i++) {
            double[] confData = detectionMat.get(i, 2);
            if (confData == null || confData.length == 0) continue;
            float confidence = (float) confData[0];
            
            if (confidence > 0.50f && confidence > maxConfidence) {
                maxConfidence = confidence;
                
                // FIX: Extract the first array element [0] before multiplying by frame dimensions
                int x1 = (int) (detectionMat.get(i, 3)[0] * frameWidth);
                int y1 = (int) (detectionMat.get(i, 4)[0] * frameHeight);
                int x2 = (int) (detectionMat.get(i, 5)[0] * frameWidth);
                int y2 = (int) (detectionMat.get(i, 6)[0] * frameHeight);

                // --- GEOMETRIC TIGHTENING MATRIX ---
                int boxWidth = x2 - x1;
                int boxHeight = y2 - y1;

                // Shrink the bounding box inward by 15% on all sides
                int paddingX = (int) (boxWidth * 0.15);
                int paddingY = (int) (boxHeight * 0.15);

                x1 = Math.max(0, x1 + paddingX);
                y1 = Math.max(0, y1 + paddingY);
                x2 = Math.min(frameWidth - 1, x2 - paddingX);
                y2 = Math.min(frameHeight - 1, y2 - paddingY);
                
                bestFaceRect = new Rect(x1, y1, x2 - x1, y2 - y1);

            }
        }
        detectionMat.release();

        if (bestFaceRect == null || bestFaceRect.width <= 0 || bestFaceRect.height <= 0) {
            imageMat.release();
            throw new IllegalArgumentException("DNN Face Detection failed.");
        }

        Mat faceMat = new Mat(imageMat, bestFaceRect);
        imageMat.release();

        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".jpg", faceMat, mob);
        faceMat.release();
        
        try (InputStream faceIs = new java.io.ByteArrayInputStream(mob.toArray())) {
            Image croppedDjlImage = ImageFactory.getInstance().fromInputStream(faceIs);
            float[] embeddings = faceRecognizer.predict(croppedDjlImage);
            mob.release();
            return embeddings;
        }
    }
    
    public AbstractMap.SimpleEntry<Member, String> isExistingMember(String username) {
    	Member member = getMemberByUsername(username);
    	if(Objects.isNull(member)) {
    		return new AbstractMap.SimpleEntry<>(null, "Unregistered Member");
    	} else if(Objects.nonNull(member)) {
    		if(Objects.isNull(member.getMobileNumber())) {
    			return new AbstractMap.SimpleEntry<>(null, "Unregistered Member");
    		} else if (Objects.nonNull(member.getIsFacialRegCompleted())
        			&& member.getIsFacialRegCompleted().equals(true)) {
    			return new AbstractMap.SimpleEntry<>(null, "Facial Registration Already completed");
    		} else if (Objects.nonNull(member.getIsActive())
    			&& member.getIsActive().equals(false)) {
    			return new AbstractMap.SimpleEntry<>(null, "Member Inactive, Please contact Admin");
    		}
    	}
    	return new AbstractMap.SimpleEntry<>(member,member.getMemberId());
    }
    
    
    
    private Member getMemberByUsername(String username) {
    	try {
			return restClient.get()
					.uri(MEMBER_GET_BY_USERNAME_ENDPOINT, username)
					.retrieve()
					.body(Member.class);
		} catch (Exception e) {
			LOGGER.error("Unable to find Member with Username : " + username);
		}
    	return new Member();
	}

    public Member getMemberByMemberId(String memberId) {
    	try {
			return restClient.get()
			        .uri(uriBuilder -> uriBuilder
			                .queryParam(MEMBER_GET_BY_MEMBERID_ENDPOINT, memberId)
			                .build())
			        .retrieve()
			        .body(Member.class);
		} catch (Exception e) {
			LOGGER.error("Unable to find Member with Member Id : " + memberId);
		}
    	return new Member();
	}
    
    private Boolean updateFaceRegistrationStatus(String memberId) {
		try {
			return restClient.put()
					.uri(MEMBER_UPDATE_FACE_REGIS_ENDPOINT, memberId)
					.retrieve()
					.body(Boolean.class);
		} catch (Exception e) {
			LOGGER.error("Unable to Update Member status for Member Id : " + memberId);
		}
    	return false;
	}

	@Transactional
    public void registerBatchOfFaces(String memberId, List<float[]> embeddingBatch) {
        // 1. Wipe out any OLD historical video registration frames for this specific member
        profileRepository.deleteByMemberId(memberId);

        List<FaceProfile> profilesToSave = new ArrayList<>();

        for (float[] embedding : embeddingBatch) {
            String vectorString = convertFloatArrayToVectorString(embedding);
            
            // 2. Scan Supabase for identity theft checks
            Optional<FaceProfile> closestMatchOpt = profileRepository.findClosestFaceMatch(vectorString);
            
            if (closestMatchOpt.isPresent()) {
                FaceProfile duplicateCandidate = closestMatchOpt.get();
                
                // CRUCIAL EXCLUSION: If the database match belongs to the member we are currently 
                // processing, ignore it! It is just one of the frames from this new batch.
                if (!duplicateCandidate.getUserId().equals(memberId)) {
                    double rawDistance = calculateEuclideanDistance(embedding, duplicateCandidate.getEmbedding());
                    if (rawDistance < 0.65) {
                        throw new IllegalStateException("Registration Rejected! This face already belongs to Member ID: " + duplicateCandidate.getUserId());
                    }
                }
            }

            // 3. Queue the frame for batch insert
            String frameId = UUID.randomUUID().toString().substring(0, 8); // Short distinct frame id
            profilesToSave.add(new FaceProfile(memberId, frameId, embedding));
        }

        // 4. Efficiently batch-save all frames to Supabase in a single transaction
        profileRepository.saveAll(profilesToSave);
    }
 
    private String convertFloatArrayToVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    // Scans database records to verify face proximity
    public RecognitionResult identifyFace(float[] targetEmbedding, double threshold) {
        List<FaceProfile> allSavedProfiles = profileRepository.findAll();
        
        if (allSavedProfiles.isEmpty()) {
            return new RecognitionResult("Unknown", 0.0);
        }

        String bestMatch = "Unknown";
        double lowestDistance = Double.MAX_VALUE; 

        for (FaceProfile savedProfile : allSavedProfiles) {
            double distance = calculateEuclideanDistance(targetEmbedding, savedProfile.getEmbedding());
            if (distance < lowestDistance) {
                lowestDistance = distance;
                bestMatch = savedProfile.getUserId();
            }
        }

        double confidencePercentage = (1.0 / (1.0 + Math.exp((lowestDistance - 0.98) / 0.12))) * 100.0;
        confidencePercentage = Math.max(0.0, Math.min(100.0, confidencePercentage));

        if (confidencePercentage >= threshold) {
            return new RecognitionResult(bestMatch, confidencePercentage);
        } else {
            return new RecognitionResult("Unknown", confidencePercentage);
        }
    }
    
    @Transactional
    public void processVideoAndRegister(String memberId, java.io.File downloadedVideoFile) throws Exception {
        long pipeStartTime = System.currentTimeMillis();
        String uniqueDir = System.getProperty("java.io.tmpdir");
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        
        Path tempVideoPath = downloadedVideoFile.toPath();
        Path frameOutputDir = Paths.get(uniqueDir, "mga_frames_" + executionId);
        
        LOGGER.info("[TRACE-START] [ID: {}] Initializing lifecycle for Member: '{}'", executionId, memberId);
        LOGGER.info("[TRACE-INPUT] [ID: {}] Staged Asset Path='{}', Size={} bytes", 
                    executionId, tempVideoPath.toAbsolutePath(), downloadedVideoFile.length());
        
        // FIX: Evaluates the local java.io.File handle descriptors directly
        if (downloadedVideoFile == null || !downloadedVideoFile.exists() || downloadedVideoFile.length() == 0) {
            LOGGER.error("[TRACE-ERROR] [ID: {}] Terminating execution: Local payload scratch wrapper is EMPTY or MISSING.", executionId);
            throw new IllegalArgumentException("Staged video file payload is empty or invalid.");
        }

        // STEP 1 Staging is completely bypassed here because the controller already wrote the bytes to disk!
        
        // 2. Prepare isolated frame directory workspace
        try {
            LOGGER.info("[TRACE-IO] [ID: {}] Creating workspace path: '{}'...", executionId, frameOutputDir.toAbsolutePath());
            Files.createDirectories(frameOutputDir);
            LOGGER.info("[TRACE-IO] [ID: {}] Directory verified. Exists: {}, IsWritable: {}", 
                        executionId, Files.exists(frameOutputDir), Files.isWritable(frameOutputDir));
        } catch (Exception e) {
            LOGGER.error("[TRACE-CRASH] [ID: {}] Failed to secure directory anchors: {}", executionId, e.getMessage(), e);
            throw e;
        }
        try {
            // 3. Assemble and execute native system level FFmpeg process wrapper
            long ffmpegStartTime = System.currentTimeMillis();
            LOGGER.info("[TRACE-FFMPEG] [ID: {}] Assembling ProcessBuilder for 3 FPS slices...", executionId);
            
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", 
                "-i", tempVideoPath.toAbsolutePath().toString(), 
                "-vf", "fps=3", 
                frameOutputDir.toAbsolutePath().toString() + "/frame_%04d.jpg"
            );
            
            LOGGER.info("[TRACE-FFMPEG] [ID: {}] Command payload: {}", executionId, String.join(" ", pb.command()));
            pb.redirectErrorStream(true);
            
            LOGGER.info("[TRACE-FFMPEG] [ID: {}] Spawning active runtime process context...", executionId);
            Process process = pb.start();
            
            // Capture native container console outputs directly into server logging channel
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    LOGGER.info("[TRACE-FFMPEG-CONSOLE] [ID: {}] {}", executionId, line);
                }
            }

            LOGGER.info("[TRACE-FFMPEG] [ID: {}] Waiting for process execution thread to resolve...", executionId);
            int exitCode = process.waitFor();
            long ffmpegDuration = System.currentTimeMillis() - ffmpegStartTime;
            LOGGER.info("[TRACE-FFMPEG] [ID: {}] Process closed. Exit Code: {} | Duration: {} ms", 
                        executionId, exitCode, ffmpegDuration);

            if (exitCode != 0) {
                LOGGER.error("[TRACE-ERROR] [ID: {}] FFmpeg exited abnormally. Halting pipeline.", executionId);
                throw new IllegalStateException("FFmpeg failed to extract frames. Exit code: " + exitCode);
            }

            // 4. Evaluate and parse generated frame file index from disk
            LOGGER.info("[TRACE-SCAN] [ID: {}] Walking output file directory tree to index assets...", executionId);
            List<Path> generatedFrames = Files.walk(frameOutputDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".jpg"))
                    .sorted()
                    .collect(Collectors.toList());

            LOGGER.info("[TRACE-SCAN] [ID: {}] Indexing finished. Total image slices found: {}", 
                        executionId, generatedFrames.size());

            if (generatedFrames.isEmpty()) {
                LOGGER.error("[TRACE-ERROR] [ID: {}] Staged folder map contains zero items.", executionId);
                throw new IllegalArgumentException("Failed to decode video stream. FFmpeg extracted 0 frames.");
            }
            List<float[]> extractedEmbeddings = new ArrayList<>();
            long processingLoopStartTime = System.currentTimeMillis();

            // 5. Initialize Predictor loop and run raw sequential image decoding
            LOGGER.info("[TRACE-MODEL] [ID: {}] Allocating Predictor instances from ZooModel...", executionId);
            try (Predictor<Image, float[]> faceRecognizer = recognitionModel.newPredictor()) {
                LOGGER.info("[TRACE-LOOP] [ID: {}] Starting matrix iterations over frame index layer...", executionId);
                
                int loopIndex = 0;
                for (Path framePath : generatedFrames) {
                    loopIndex++;
                    long frameStartTime = System.currentTimeMillis();
                    String framePathStr = framePath.toAbsolutePath().toString();
                    
                    LOGGER.info("[TRACE-FRAME-START] [ID: {}] Frame {}/{} -> Reading: '{}' ({} bytes)", 
                                executionId, loopIndex, generatedFrames.size(), framePathStr, Files.size(framePath));
                    
                    Mat frameMat = Imgcodecs.imread(framePathStr);
                    LOGGER.info("[TRACE-FRAME-CV] [ID: {}] Frame {}/{} -> Matrix size: W={}, H={}, Empty={}", 
                                executionId, loopIndex, generatedFrames.size(), frameMat.cols(), frameMat.rows(), frameMat.empty());

                    if (frameMat.empty()) {
                        LOGGER.warn("[TRACE-FRAME-WARN] [ID: {}] Loaded an empty map. Skipping frame {}.", executionId, loopIndex);
                        frameMat.release();
                        continue;
                    }

                    try {
                        LOGGER.info("[TRACE-FRAME-DNN] [ID: {}] Frame {}/{} -> Routing to extraction pipeline...", executionId, loopIndex);
                        float[] embeddings = extractEmbeddingsFromMatFrame(frameMat, faceRecognizer);
                        
                        extractedEmbeddings.add(embeddings);
                        LOGGER.info("[TRACE-FRAME-OK] [ID: {}] Frame {}/{} -> Extracted vector size count: {}", 
                                    executionId, loopIndex, extractedEmbeddings.size(), embeddings.length);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("[TRACE-FRAME-SKIP] [ID: {}] Frame {}/{} -> Gracefully skipped: {}", 
                                    executionId, loopIndex, e.getMessage());
                    } catch (Exception modelEx) {
                        LOGGER.error("[TRACE-FRAME-FAIL] [ID: {}] Frame {}/{} -> Neural loop failure: {}", 
                                    executionId, loopIndex, modelEx.getMessage(), modelEx);
                    } finally {
                        frameMat.release(); // Avoid native C++ memory leaks inside standard loops
                        long frameDuration = System.currentTimeMillis() - frameStartTime;
                        LOGGER.info("[TRACE-FRAME-END] [ID: {}] Frame {}/{} -> Segment time: {} ms", 
                                    executionId, loopIndex, generatedFrames.size(), frameDuration);
                    }
                }
            }

            long processingLoopDuration = System.currentTimeMillis() - processingLoopStartTime;
            LOGGER.info("[TRACE-COMPUTE] [ID: {}] Loop Complete: Valid Elements={}, Total Frames={} | Time: {} ms", 
                        executionId, extractedEmbeddings.size(), generatedFrames.size(), processingLoopDuration);

            if (extractedEmbeddings.isEmpty()) {
                LOGGER.error("[TRACE-ERROR] [ID: {}] Zero human face profiles found across dataset.", executionId);
                throw new IllegalArgumentException("Biometric Exception: No human faces detected inside video.");
            }

            // 6. Persist calculated embedding collections out to Database tables
            long dbStartTime = System.currentTimeMillis();
            LOGGER.info("[TRACE-DB] [ID: {}] Shipping biometric models down to database query layer...", executionId);
            this.registerBatchOfFaces(memberId, extractedEmbeddings);
            
            LOGGER.info("[TRACE-DB] [ID: {}] Updating rest status completion flags...", executionId);
            updateFaceRegistrationStatus(memberId);
            LOGGER.info("[TRACE-DB] [ID: {}] DB step finished cleanly. Time: {} ms", executionId, (System.currentTimeMillis() - dbStartTime));

        } finally {
            // 7. Housekeeping / Clean absolute scratch data to avoid container disk space bloat crashes
            LOGGER.info("[TRACE-CLEANUP] [ID: {}] Initializing terminal sweeping routines...", executionId);
            
            boolean videoDeleted = Files.deleteIfExists(tempVideoPath);
            LOGGER.info("[TRACE-CLEANUP] [ID: {}] Scratch upload video container erased. Status: {}", executionId, videoDeleted);
            
            if (Files.exists(frameOutputDir)) {
                long deletedFramesCount = Files.walk(frameOutputDir)
                     .map(Path::toFile)
                     .filter(java.io.File::isFile)
                     .peek(f -> f.delete())
                     .count();
                LOGGER.info("[TRACE-CLEANUP] [ID: {}] Erased {} image items from staging workspace.", executionId, deletedFramesCount);
                
                boolean dirDeleted = frameOutputDir.toFile().delete();
                LOGGER.info("[TRACE-CLEANUP] [ID: {}] Workspace directory deleted. Status: {}", executionId, dirDeleted);
            }
            
            LOGGER.info("[TRACE-END] [ID: {}] Process complete. Total pipeline duration: {} ms", executionId, (System.currentTimeMillis() - pipeStartTime));
        }
    }

    
    /**
     * Refactored helper method to process an internal OpenCV Mat frame directly in server RAM
     */
    private float[] extractEmbeddingsFromMatFrame(Mat imageMat, Predictor<Image, float[]> faceRecognizer) throws Exception {
        Mat blob = Dnn.blobFromImage(imageMat, 1.0, new Size(300, 300), new Scalar(104.0, 177.0, 123.0), false, false);
        dnnFaceDetector.setInput(blob);
        Mat detections = dnnFaceDetector.forward();
        blob.release();

        Mat detectionMat = detections.reshape(1, detections.size(2));
        detections.release();

        int frameWidth = imageMat.cols();
        int frameHeight = imageMat.rows();
        Rect bestFaceRect = null;
        float maxConfidence = 0.0f;

        for (int i = 0; i < detectionMat.rows(); i++) {
            double[] confData = detectionMat.get(i, 2);
            if (confData == null || confData.length == 0) continue;
            float confidence = (float) confData[0];
            
            if (confidence > 0.50f && confidence > maxConfidence) {
                maxConfidence = confidence;
                
                int x1 = (int) (detectionMat.get(i, 3)[0] * frameWidth);
                int y1 = (int) (detectionMat.get(i, 4)[0] * frameHeight);
                int x2 = (int) (detectionMat.get(i, 5)[0] * frameWidth);
                int y2 = (int) (detectionMat.get(i, 6)[0] * frameHeight);

                int boxWidth = x2 - x1;
                int boxHeight = y2 - y1;

                int paddingX = (int) (boxWidth * 0.15);
                int paddingY = (int) (boxHeight * 0.15);

                x1 = Math.max(0, x1 + paddingX);
                y1 = Math.max(0, y1 + paddingY);
                x2 = Math.min(frameWidth - 1, x2 - paddingX);
                y2 = Math.min(frameHeight - 1, y2 - paddingY);
                
                bestFaceRect = new Rect(x1, y1, x2 - x1, y2 - y1);
            }
        }
        
        // FIX: Replaced .close() with .release() to fix the compilation error
        detectionMat.release(); 

        if (bestFaceRect == null || bestFaceRect.width <= 0 || bestFaceRect.height <= 0) {
            throw new IllegalArgumentException("No face detected in this specific frame slice.");
        }

        Mat faceMat = new Mat(imageMat, bestFaceRect);
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".jpg", faceMat, mob);
        faceMat.release();
        
        try (InputStream faceIs = new java.io.ByteArrayInputStream(mob.toArray())) {
            Image croppedDjlImage = ImageFactory.getInstance().fromInputStream(faceIs);
            float[] embeddings = faceRecognizer.predict(croppedDjlImage);
            mob.release();
            return embeddings;
        }
    }

    // Administrative Method: Fetch all unique profile identifiers
    public List<String> getAllRegisteredUserIds() {
        return profileRepository.findAll().stream()
                .map(FaceProfile::getUserId)
                .distinct()
                .collect(Collectors.toList());
    }

    // Administrative Method: Completely remove a target user profile
    @Transactional
    public void removeUserProfile(String userId) {
        profileRepository.deleteByMemberId(userId);
    }

    private double calculateEuclideanDistance(float[] vectorA, float[] vectorB) {
        double sum = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            double diff = vectorA[i] - vectorB[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    @PreDestroy
    public void cleanup() {
        if (faceRecognizer != null) faceRecognizer.close();
        if (recognitionModel != null) recognitionModel.close();
    }
}