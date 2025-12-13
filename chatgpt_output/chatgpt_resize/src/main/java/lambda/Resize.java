package lambda;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import javax.imageio.ImageIO;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import saaf.Inspector;
import saaf.Response;

public class Resize implements RequestHandler<HashMap<String, Object>, HashMap<String, Object>> {

    private final S3Client s3Client = S3Client.builder().build();

    public HashMap<String, Object> handleRequest(HashMap<String, Object> request, Context context) {
        // Initialize SAAF Inspector
        Inspector inspector = new Inspector();
        inspector.inspectAll();

        try {
            // Extract parameters
            String bucket = (String) request.get("bucket");
            String key = (String) request.get("key");

            inspector.addAttribute("bucket", bucket);
            inspector.addAttribute("key", key);
            context.getLogger().log("Processing: " + bucket + "/" + key);

            // Download image from S3
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            byte[] inputBytes = objectBytes.asByteArray();
            String contentType = objectBytes.response().contentType();
            String format = detectFormat(contentType, key);

            // Read image
            BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(inputBytes));
            if (srcImage == null) {
                throw new RuntimeException("Failed to read image from S3 object");
            }

            int srcWidth = srcImage.getWidth();
            int srcHeight = srcImage.getHeight();
            inspector.addAttribute("originalWidth", srcWidth);
            inspector.addAttribute("originalHeight", srcHeight);

            // Resize if needed
            byte[] outputBytes;
            String outKey = "chatgpt_resized/" + key;

            if (srcWidth <= 800) {
                outputBytes = inputBytes;
                inspector.addAttribute("resized", 0);  // Use 0/1 instead of boolean for faas_runner compatibility
            } else {
                int newWidth = 800;
                int newHeight = (int) Math.round((double) srcHeight * ((double) newWidth / (double) srcWidth));
                BufferedImage dest = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = dest.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(srcImage, 0, 0, newWidth, newHeight, null);
                g2.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(dest, format, baos);
                outputBytes = baos.toByteArray();

                inspector.addAttribute("resized", 1);  // Use 0/1 instead of boolean for faas_runner compatibility
                inspector.addAttribute("newWidth", newWidth);
                inspector.addAttribute("newHeight", newHeight);
            }

            // Upload to S3
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(outKey)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(outputBytes));

            context.getLogger().log("Resized image uploaded to: " + bucket + "/" + outKey);

            // Set bucket and key to OUTPUT values for pipeline chaining
            inspector.addAttribute("bucket", bucket);
            inspector.addAttribute("key", outKey);
            inspector.addAttribute("message", "Image resized successfully");

            Response response = new Response();
            response.setValue("Resize completed successfully!");
            inspector.consumeResponse(response);

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            inspector.addAttribute("error", e.getMessage());
            throw new RuntimeException(e);
        }

        // Collect final metrics
        inspector.inspectAllDeltas();
        return inspector.finish();
    }

    private String detectFormat(String contentType, String key) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.equals("image/jpeg") || ct.equals("image/jpg")) return "jpeg";
            if (ct.equals("image/png")) return "png";
            if (ct.equals("image/gif")) return "gif";
            if (ct.equals("image/bmp")) return "bmp";
        }
        if (key != null) {
            int idx = key.lastIndexOf('.');
            if (idx >= 0 && idx < key.length() - 1) {
                String ext = key.substring(idx + 1).toLowerCase();
                if (ext.equals("jpg")) return "jpeg";
                return ext;
            }
        }
        return "jpeg";
    }
}