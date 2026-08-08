package com.mygym.app.facialrecognition.service;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FaceDetectionTranslator implements Translator<Image, DetectedObjects> {

    private final double threshold = 0.70; // Filter out bounding boxes with less than 70% face confidence

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        // The Ultra-Light detector expects exactly a 320x240 RGB image
        Image resized = input.resize(320, 240, true);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        
        // Convert to float, normalize, and transpose from HWC to CHW format
        array = array.div(255.0f).sub(0.5f).div(0.5f);
        array = array.transpose(2, 0, 1);
        
        return new NDList(array);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        // The model returns two arrays: scores (probabilities) and boxes (coordinates)
        NDArray scores = list.get(0);
        NDArray boxes = list.get(1);
        
        // Flatten the batch dimension down to a readable floating point array
        float[] scoreArr = scores.get(0).toFloatArray();
        float[] boxArr = boxes.get(0).toFloatArray();
        
        List<String> classNames = Arrays.asList("face");
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boundingBoxes = new ArrayList<>();

        // Iterate through all potential bounding box proposals returned by the engine
        for (int i = 0; i < scoreArr.length / 2; i++) {
            float faceScore = scoreArr[i * 2 + 1]; // Odd indices contain face confidence scores
            
            if (faceScore > threshold) {
                probabilities.add((double) faceScore);
                
                // Read the corresponding bounding box coordinate parameters
                float xMin = boxArr[i * 4];
                float yMin = boxArr[i * 4 + 1];
                float xMax = boxArr[i * 4 + 2];
                float yMax = boxArr[i * 4 + 3];
                
                boundingBoxes.add(new Rectangle(xMin, yMin, xMax - xMin, yMax - yMin));
            }
        }
        
        return new DetectedObjects(classNames, probabilities, boundingBoxes);
    }
}
