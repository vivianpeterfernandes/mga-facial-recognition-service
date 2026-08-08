package com.mygym.app.facialrecognition.service;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

public class FaceNetTranslator implements Translator<Image, float[]> {

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        // 1. Resize image matrix to 160x160 pixels
        Image resized = input.resize(160, 160, true);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        
        // 2. Change format structural layout from HWC to CHW
        array = array.transpose(2, 0, 1);
        
        // 3. Convert int pixel elements to float matrix arrays
        array = array.toType(ai.djl.ndarray.types.DataType.FLOAT32, false);
        
        // 4. FIX: Compute Per-Image Standard Deviation manually using DJL mathematical operations
        NDArray mean = array.mean();
        
        // Variance calculation: Mean of squared deviations -> mean( (X - mean)^2 )
        NDArray variance = array.sub(mean).square().mean();
        
        // Standard Deviation calculation: Square Root of Variance
        NDArray std = variance.sqrt();
        
        // Prevent division-by-zero calculation anomalies on perfectly blank pixels
        float eps = 1e-5f;
        NDArray adjustedStd = std.maximum(eps);
        
        // Apply Vector Normalization formula: (x - mean) / max(std, eps)
        array = array.sub(mean).div(adjustedStd);
        
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // Extract the resulting normalized 512-d embedding array
        return list.get(0).toFloatArray();
    }
}
