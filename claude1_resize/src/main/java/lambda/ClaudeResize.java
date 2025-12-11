package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import saaf.Inspector;
import saaf.Response;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;

/**
 * Resize Conversion Lambda Function with SAAF
 *
 * @author Wes Lloyd
 * @author Robert Cordingly
 * @author Justin Le
 * @author Claude
 */
public class ClaudeResize implements RequestHandler<HashMap<String, Object>, HashMap<String, Object>> {

    private final S3Client s3Client = S3Client.builder().build();

    /**
     * Lambda Function Handler
     * 
     * @param request Hashmap containing request JSON attributes.
     * @param context
     * @return HashMap that Lambda will automatically convert into JSON.
     */
    public HashMap<String, Object> handleRequest(HashMap<String, Object> request, Context context) {

        // Collect initial data.
        Inspector inspector = new Inspector();
        inspector.inspectAll();

        // ****************START FUNCTION IMPLEMENTATION*************************

        try {
            // Extract parameters from request
            String bucket = (String) request.get("bucket");
            String key = (String) request.get("key");

            inspector.addAttribute("bucket", bucket);
            inspector.addAttribute("key", key);
            context.getLogger().log("Processing: " + bucket + "/" + key);

            // Download image from S3
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build());

            byte[] imageBytes = objectBytes.asByteArray();
            String contentType = objectBytes.response().contentType();

            // Read the image
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (originalImage == null) {
                throw new RuntimeException("Failed to read image");
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            inspector.addAttribute("originalWidth", originalWidth);
            inspector.addAttribute("originalHeight", originalHeight);

            // Resize image if needed
            BufferedImage resizedImage;
            if (originalWidth <= 800) {
                resizedImage = originalImage;
                inspector.addAttribute("resized", false);
            } else {
                int newWidth = 800;
                int newHeight = (int) ((double) originalHeight * newWidth / originalWidth);

                resizedImage = new BufferedImage(newWidth, newHeight, originalImage.getType());
                Graphics2D g = resizedImage.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
                g.dispose();

                inspector.addAttribute("resized", true);
                inspector.addAttribute("newWidth", newWidth);
                inspector.addAttribute("newHeight", newHeight);
            }

            // Convert image to bytes
            String formatName = getFormatName(key);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, formatName, outputStream);
            byte[] resizedBytes = outputStream.toByteArray();

            // Upload to S3
            String outputKey = "claude_resized/" + key;
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(outputKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(resizedBytes));

            context.getLogger().log("Resized image uploaded to: " + bucket + "/" + outputKey);

            // Create response object
            Response response = new Response();
            response.setValue("Image processed successfully!");

            inspector.addAttribute("outputBucket", bucket);
            inspector.addAttribute("outputKey", outputKey);
            inspector.addAttribute("message", "Image resized and uploaded successfully");

            inspector.consumeResponse(response);

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            inspector.addAttribute("error", e.getMessage());
            throw new RuntimeException(e);
        }

        // ****************END FUNCTION IMPLEMENTATION***************************

        // Collect final information such as total runtime and cpu deltas.
        inspector.inspectAllDeltas();
        return inspector.finish();
    }

    /**
     * Determines the image format based on the file extension.
     * Supports PNG, GIF, and BMP formats, defaulting to JPG for unrecognized
     * extensions.
     * 
     * @param key The S3 object key or filename containing the file extension
     * @return The image format name as a string ("png", "gif", "bmp", or "jpg")
     */
    private String getFormatName(String key) {
        String lowerKey = key.toLowerCase();
        if (lowerKey.endsWith(".png"))
            return "png";
        if (lowerKey.endsWith(".gif"))
            return "gif";
        if (lowerKey.endsWith(".bmp"))
            return "bmp";
        return "jpg";
    }
}