package lambda;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
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

public class Rotate implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (event == null || !event.containsKey("bucket") || !event.containsKey("key")) {
            throw new RuntimeException("Event must contain 'bucket' and 'key'");
        }
        
        String bucket = (String) event.get("bucket");
        String key = (String) event.get("key");
        String outKey = "chatgpt_rotated/" + key;
        
        try (S3Client s3 = S3Client.create()) {
            GetObjectRequest getReq = GetObjectRequest.builder().bucket(bucket).key(key).build();
            ResponseBytes<GetObjectResponse> bytes = s3.getObject(getReq, software.amazon.awssdk.core.sync.ResponseTransformer.toBytes());
            byte[] inputBytes = bytes.asByteArray();
            String contentType = bytes.response().contentType();
            String format = detectFormat(contentType, key);
            
            if (format == null) throw new RuntimeException("Cannot detect image format");
            
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(inputBytes));
            if (src == null) throw new RuntimeException("Cannot read image");
            
            int w = src.getWidth();
            int h = src.getHeight();
            BufferedImage dst = new BufferedImage(h, w, src.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : src.getType());
            Graphics2D g2 = dst.createGraphics();
            AffineTransform at = new AffineTransform();
            at.translate(h, 0);
            at.rotate(Math.toRadians(90));
            g2.drawImage(src, at, null);
            g2.dispose();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(dst, format, baos)) throw new RuntimeException("Failed to encode image");
            byte[] outputBytes = baos.toByteArray();
            
            PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(outKey)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .contentLength((long) outputBytes.length)
                .build();
            s3.putObject(putReq, RequestBody.fromBytes(outputBytes));
            
            Map<String, Object> result = new HashMap<>();
            result.put("bucket", bucket);
            result.put("key", outKey);
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String detectFormat(String contentType, String key) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.equals("image/jpeg") || ct.equals("image/jpg")) return "jpeg";
            if (ct.equals("image/png")) return "png";
            if (ct.equals("image/gif")) return "gif";
            if (ct.equals("image/bmp")) return "bmp";
            if (ct.equals("image/webp")) return "webp";
            if (ct.startsWith("image/")) return ct.substring(6);
        }
        int idx = key.lastIndexOf('.');
        if (idx >= 0 && idx < key.length() - 1) {
            String ext = key.substring(idx + 1).toLowerCase();
            if (ext.equals("jpg")) return "jpeg";
            return ext;
        }
        return null;
    }
}