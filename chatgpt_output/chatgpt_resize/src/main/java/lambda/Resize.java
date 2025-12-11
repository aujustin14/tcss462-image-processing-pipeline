package lambda;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import javax.imageio.ImageIO;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.Context;

public class Resize implements RequestHandler<Map<String,Object>, Map<String,Object>> {
    public Map<String,Object> handleRequest(Map<String,Object> event, Context context) {
        String bucket = null;
        String key = null;
        try {
            if (event == null || !event.containsKey("bucket") || !event.containsKey("key")) {
                context.getLogger().log("Invalid event: missing 'bucket' or 'key'");
                throw new RuntimeException("Event must contain 'bucket' and 'key'");
            }
            Object b = event.get("bucket");
            Object k = event.get("key");
            if (!(b instanceof String) || !(k instanceof String)) {
                context.getLogger().log("Invalid types for 'bucket' or 'key'");
                throw new RuntimeException("'bucket' and 'key' must be strings");
            }
            bucket = (String) b;
            key = (String) k;
            String outKey = "chatgpt_resized/" + key;
            try (S3Client s3 = S3Client.create()) {
                GetObjectRequest getReq = GetObjectRequest.builder().bucket(bucket).key(key).build();
                ResponseBytes<GetObjectResponse> objectBytes = s3.getObject(getReq, software.amazon.awssdk.core.sync.ResponseTransformer.toBytes());
                byte[] inputBytes = objectBytes.asByteArray();
                String contentType = objectBytes.response().contentType();
                String format = detectFormat(contentType, key);
                if (format == null) {
                    context.getLogger().log("Unable to detect image format for key: " + key);
                    throw new RuntimeException("Unsupported or unknown image format");
                }
                BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(inputBytes));
                if (srcImage == null) {
                    context.getLogger().log("ImageIO failed to read image: " + key);
                    throw new RuntimeException("Failed to read image from S3 object");
                }
                int srcWidth = srcImage.getWidth();
                int srcHeight = srcImage.getHeight();
                byte[] outputBytes;
                if (srcWidth <= 800) {
                    outputBytes = inputBytes;
                } else {
                    int newWidth = 800;
                    int newHeight = (int) Math.round((double) srcHeight * ((double) newWidth / (double) srcWidth));
                    BufferedImage dest = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = dest.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(srcImage, 0, 0, newWidth, newHeight, null);
                    g2.dispose();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    boolean wrote = ImageIO.write(dest, format, baos);
                    if (!wrote) {
                        throw new RuntimeException("ImageIO failed to write resized image");
                    }
                    outputBytes = baos.toByteArray();
                }
                PutObjectRequest putReq = PutObjectRequest.builder().bucket(bucket).key(outKey).contentType(contentType != null ? contentType : "application/octet-stream").contentLength((long) outputBytes.length).build();
                s3.putObject(putReq, RequestBody.fromBytes(outputBytes));
                Map<String,Object> result = new HashMap<>();
                result.put("bucket", bucket);
                result.put("key", outKey);

                return result;
            } catch (S3Exception e) {
                context.getLogger().log("S3 error: " + e.awsErrorDetails().errorMessage());
                throw new RuntimeException("S3 error: " + e.awsErrorDetails().errorMessage(), e);
            } catch (IOException e) {
                context.getLogger().log("IO error: " + e.getMessage());
                throw new RuntimeException("IO error while processing image", e);
            }
        } catch (RuntimeException e) {
            context.getLogger().log("Handler failed: " + e.getMessage());
            throw e;
            } catch (Exception e) {
            String msg = "Unexpected error: " + e.getMessage();
            if (context != null) context.getLogger().log(msg);
            throw new RuntimeException(msg, e);
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
            if (ct.startsWith("image/")) {
                String subtype = ct.substring(6);
                return subtype;
            }
        }
        if (key != null) {
            int idx = key.lastIndexOf('.');
            if (idx >= 0 && idx < key.length() - 1) {
                String ext = key.substring(idx + 1).toLowerCase();
                if (ext.equals("jpg")) return "jpeg";
                return ext;
            }
        }
        return null;
    }
}