package com.mygym.app.facialrecognition.controller;

import static com.mygym.app.facialrecognition.util.FacialConstants.FACIAL_REQUEST_MAPPING;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.mygym.app.facialrecognition.model.Member;
import com.mygym.app.facialrecognition.model.RecognitionResult;
import com.mygym.app.facialrecognition.service.FaceRecognitionService;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@RestController
@RequestMapping(FACIAL_REQUEST_MAPPING)
public class FaceController {
	
    private static final Logger LOGGER = LoggerFactory.getLogger(FaceController.class);
    private final FaceRecognitionService faceService;

    public FaceController(FaceRecognitionService faceService) {
        this.faceService = faceService;
    }
    
    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Presigner s3Presigner;

    @Value("${aws.s3.bucket:gym-faces}")
    private String bucketName;
    
    @GetMapping("/request-url")
    public ResponseEntity<Map<String, String>> getPreSignedUploadUrl() {
        String uniqueFileKey = "registrations/" + UUID.randomUUID().toString() + ".mp4";

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(r -> r.bucket(bucketName).key(uniqueFileKey).contentType("video/mp4"))
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);

        return ResponseEntity.ok(Map.of(
                "uploadUrl", presignedRequest.url().toString(),
                "fileKey", uniqueFileKey
        ));
    }

    @PostMapping("/register-video")
    public ResponseEntity<String> registerVideoWithMemberId(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String fileKey = payload.get("fileKey");

        if (username == null || fileKey == null) {
            return ResponseEntity.badRequest().body("Missing username or fileKey parameters.");
        }

        LOGGER.info("Registration payload reference linked for User: {}", username);
    	AbstractMap.SimpleEntry<Member, String> entryStatus = faceService.isExistingMember(username);
        if (Objects.isNull(entryStatus.getKey())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(entryStatus.getValue());
        }
        
        String memberId = entryStatus.getValue();
        File temporaryLocalFile = null;
        
        try {
            temporaryLocalFile = File.createTempFile("mga_async_reg_", ".mp4");

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();

            // RESOLVES UNMARSHALLING CRASH: Read raw input bytes from network sockets to bypass empty AWS headers
            try (ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getObjectRequest)) {
                Files.copy(s3Stream, temporaryLocalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            faceService.processVideoAndRegister(memberId, temporaryLocalFile);
            return ResponseEntity.ok("Video stream sliced, vectors calculated, and profiles registered successfully.");
            
        } catch (IllegalStateException e) {
            LOGGER.error("Validation breakdown: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Pipeline panic: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Video pipeline failure: " + e.getMessage());
        } finally {
            // ZERO-LEAK CLEANUP LOOP: Instantly purge local container and cloud bucket items
            if (temporaryLocalFile != null && temporaryLocalFile.exists()) {
                temporaryLocalFile.delete();
            }
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(fileKey).build());
            } catch (Exception ignored) {}

            System.gc();
            System.runFinalization();
        }
    }

    @PostMapping("/identify")
    public ResponseEntity<Map<String, Object>> identifyFace(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        try {
            float[] targetEmbedding = faceService.extractEmbeddingsFromFace(file);
            double targetThreshold = 75.0; 
            
            RecognitionResult result = faceService.identifyFace(targetEmbedding, targetThreshold);
            
            response.put("status", "success");
            response.put("identifiedUser", result.userId());
            response.put("confidenceScore", String.format("%.2f%%", result.confidenceScore()));
            response.put("requiredThreshold", targetThreshold + "%");

            if (!"UNKNOWN".equals(result.userId())) {
                try {
                    Member details = faceService.getMemberByMemberId(result.userId());
                    if (details != null && details.getName() != null) {
                        response.put("memberName", details.getName());
                    } else {
                        response.put("memberName", "Active Gym Member");
                    }
                } catch (Exception e) {
                    response.put("memberName", "Active Gym Member");
                }
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/profiles")
    public ResponseEntity<List<String>> listProfiles() {
        return ResponseEntity.ok(faceService.getAllRegisteredUserIds());
    }

    @DeleteMapping("/remove")
    public ResponseEntity<Map<String, String>> removeProfile(@RequestParam("userId") String userId) {
        Map<String, String> response = new HashMap<>();
        faceService.removeUserProfile(userId);
        response.put("status", "success");
        response.put("message", "Successfully erased all face vector rows for user: " + userId);
        return ResponseEntity.ok(response);
    }    
}
