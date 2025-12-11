package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import saaf.Inspector;
import saaf.Response;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;

/**
 * Grayscale Conversion Lambda Function with SAAF
 *
 * @author Wes Lloyd
 * @author Robert Cordingly
 * @author Justin Le
 * @author Claude
 */
public class ClaudeGrayscale implements RequestHandler<HashMap<String, Object>, HashMap<String, Object>> {

    private final S3Client s3Client = S3Client.builder().build();

    /**
     * Lambda Function Handler
     * 
     * @param request Hashmap containing request JSON attributes (bucket, key).
     * @param context
     * @return HashMap that Lambda will automatically convert into JSON.
     */
    public HashMap<String, Object> handleRequest(HashMap<String, Object> request, Context context) {

        // Collect initial data.
        Inspector inspector = new Inspector();
        inspector.inspectAll();

        // ****************START FUNCTION IMPLEMENTATION*************************

        try {
            // Extract S3 bucket and key from request
            String bucket = (String) request.get("bucket");
            String key = (String) request.get("key");

            // Add input parameters to SAAF output
            inspector.addAttribute("inputBucket", bucket);
            inspector.addAttribute("inputKey", key);

            // Get the image from S3
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            byte[] imageBytes = s3Client.getObject(getRequest).readAllBytes();

            // Read the input image
            BufferedImage inputImage = ImageIO.read(new ByteArrayInputStream(imageBytes));

            int width = inputImage.getWidth();
            int height = inputImage.getHeight();

            inspector.addAttribute("imageWidth", width);
            inspector.addAttribute("imageHeight", height);
            inspector.addAttribute("originalColorModel", inputImage.getColorModel().toString());

            // Determine image format
            String formatName = getImageFormat(key);
            inspector.addAttribute("imageFormat", formatName);

            // Convert to grayscale
            ColorSpace grayColorSpace = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            ColorConvertOp colorConvertOp = new ColorConvertOp(grayColorSpace, null);
            BufferedImage grayscaleImage = colorConvertOp.filter(inputImage, null);

            inspector.addAttribute("grayscaleColorModel", grayscaleImage.getColorModel().toString());

            // Write grayscale image to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(grayscaleImage, formatName, outputStream);
            byte[] outputBytes = outputStream.toByteArray();

            inspector.addAttribute("inputSize", imageBytes.length);
            inspector.addAttribute("outputSize", outputBytes.length);

            // Upload grayscale image to S3
            String outputKey = "grayscale/" + key;
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(outputKey)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(outputBytes));

            // Add output information to SAAF
            inspector.addAttribute("outputBucket", bucket);
            inspector.addAttribute("outputKey", outputKey);
            inspector.addAttribute("status", "success");

            // Create response object
            Response response = new Response();
            response.setValue("Image successfully converted to grayscale");

            inspector.consumeResponse(response);

        } catch (Exception e) {
            inspector.addAttribute("error", e.getMessage());
            inspector.addAttribute("errorType", e.getClass().getName());
            inspector.addAttribute("status", "failure");

            Response response = new Response();
            response.setValue("Error converting image to grayscale: " + e.getMessage());
            inspector.consumeResponse(response);
        }

        // ****************END FUNCTION IMPLEMENTATION***************************

        // Collect final information such as total runtime and cpu deltas.
        inspector.inspectAllDeltas();
        return inspector.finish();
    }

    /**
     * Helper method to extract image format from file key
     * 
     * @param key S3 object key
     * @return Image format string
     */
    private String getImageFormat(String key) {
        int lastDot = key.lastIndexOf('.');
        if (lastDot > 0) {
            return key.substring(lastDot + 1).toLowerCase();
        }
        return "png";
    }
}