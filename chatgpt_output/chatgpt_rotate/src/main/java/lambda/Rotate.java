package lambda;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import javax.imageio.ImageIO;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;
import saaf.Inspector;
import saaf.Response;

public class Rotate implements RequestHandler<HashMap<String, Object>, HashMap<String, Object>> {

    private final S3Client s3Client = S3Client.builder().build();

    public HashMap<String, Object> handleRequest(HashMap<String, Object> request, Context context) {
        Inspector inspector = new Inspector();
        inspector.inspectAll();

        try {
            String bucket = (String) request.get("bucket");
            String key = (String) request.get("key");

            inspector.addAttribute("bucket", bucket);
            inspector.addAttribute("key", key);
            context.getLogger().log("Processing: " + bucket + "/" + key);

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            byte[] inputBytes = objectBytes.asByteArray();
            String contentType = objectBytes.response().contentType();
            String format = detectFormat(contentType, key);

            BufferedImage src = ImageIO.read(new ByteArrayInputStream(inputBytes));
            if (src == null) {
                throw new RuntimeException("Failed to read image from S3 object");
            }

            int w = src.getWidth();
            int h = src.getHeight();
            inspector.addAttribute("originalWidth", w);
            inspector.addAttribute("originalHeight", h);

            BufferedImage dst = new BufferedImage(h, w, src.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : src.getType());
            Graphics2D g2 = dst.createGraphics();
            AffineTransform at = new AffineTransform();
            at.translate(h, 0);
            at.rotate(Math.toRadians(90));
            g2.drawImage(src, at, null);
            g2.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(dst, format, baos);
            byte[] outputBytes = baos.toByteArray();

            String outKey = "chatgpt_rotated/" + key;
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(outKey)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(outputBytes));

            context.getLogger().log("Rotated image uploaded to: " + bucket + "/" + outKey);

            // Set bucket and key to OUTPUT values for pipeline chaining
            inspector.addAttribute("bucket", bucket);
            inspector.addAttribute("key", outKey);
            inspector.addAttribute("rotatedWidth", h);
            inspector.addAttribute("rotatedHeight", w);
            inspector.addAttribute("message", "Image rotated 90 degrees clockwise");

            Response response = new Response();
            response.setValue("Rotate completed successfully!");
            inspector.consumeResponse(response);

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            inspector.addAttribute("error", e.getMessage());
            throw new RuntimeException(e);
        }

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

