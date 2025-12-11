package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import saaf.Inspector;
import saaf.Response;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;

/**
 * Image Rotation Lambda Function with SAAF
 *
 * @author Wes Lloyd
 * @author Robert Cordingly
 * @author Justin Le
 * @author Claude
 */
public class ClaudeRotate implements RequestHandler<HashMap<String, Object>, HashMap<String, Object>> {

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

            // Create S3 client
            S3Client s3Client = S3Client.builder().build();

            // Get the image from S3
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);

            // Read and rotate the image
            byte[] imageBytes = s3Object.readAllBytes();
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageBytes));

            int width = originalImage.getWidth();
            int height = originalImage.getHeight();

            inspector.addAttribute("originalWidth", width);
            inspector.addAttribute("originalHeight", height);

            // Create rotated image (90 degrees clockwise)
            BufferedImage rotatedImage = new BufferedImage(height, width, originalImage.getType());

            Graphics2D g2d = rotatedImage.createGraphics();
            AffineTransform transform = new AffineTransform();
            transform.translate(height / 2.0, width / 2.0);
            transform.rotate(Math.PI / 2);
            transform.translate(-width / 2.0, -height / 2.0);
            g2d.setTransform(transform);
            g2d.drawImage(originalImage, 0, 0, null);
            g2d.dispose();

            // Convert rotated image to bytes
            String format = key.substring(key.lastIndexOf('.') + 1);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(rotatedImage, format, outputStream);
            byte[] rotatedBytes = outputStream.toByteArray();

            // Upload rotated image to S3
            String outputKey = "rotated/" + key;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(outputKey)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(rotatedBytes));

            // Add output information to SAAF
            inspector.addAttribute("outputBucket", bucket);
            inspector.addAttribute("outputKey", outputKey);
            inspector.addAttribute("rotatedWidth", height);
            inspector.addAttribute("rotatedHeight", width);
            inspector.addAttribute("imageFormat", format);

            // Create response object
            Response response = new Response();
            response.setValue("Image successfully rotated 90 degrees clockwise");

            inspector.consumeResponse(response);

        } catch (Exception e) {
            inspector.addAttribute("error", e.getMessage());
            inspector.addAttribute("errorType", e.getClass().getName());

            Response response = new Response();
            response.setValue("Error rotating image: " + e.getMessage());
            inspector.consumeResponse(response);
        }

        // ****************END FUNCTION IMPLEMENTATION***************************

        // Collect final information such as total runtime and cpu deltas.
        inspector.inspectAllDeltas();
        return inspector.finish();
    }
}