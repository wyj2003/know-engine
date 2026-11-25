package know.engine.document.service;

import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class FileStorageService {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    // 确保 bucket 存在
    private void createBucketIfNotExists(boolean publicRead) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());

            // 设置 bucket 策略为公共读
            if (publicRead) {
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .config(policy)
                                .build()
                );
            }
        }
    }

    // 上传文件
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        createBucketIfNotExists(true);// 这里可根据你自己的情况改成false，如果改成false，需要在这个方法最后调一次getPresignedUrl
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(resolveContentType(objectName, file.getContentType()))
                .build());
        return String.format("%s/%s/%s", endpoint, bucketName, objectName);

    }

    /**
     * 上传文件
     */
    public String uploadFile(String objectName, byte[] content, String contentType) throws Exception {
        createBucketIfNotExists(true);
        try (InputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(stream, content.length, -1)
                            .contentType(resolveContentType(objectName, contentType))
                            .build()
            );

            return String.format("%s/%s/%s", endpoint, bucketName, objectName);
        }
    }

    private String resolveContentType(String objectName, String contentType) {
        String lowerName = objectName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            // Markdown 处理流程使用 UTF-8；声明编码，避免浏览器按本地编码显示中文。
            return "text/markdown; charset=UTF-8";
        }
        return contentType;
    }

    // 下载文件（返回 InputStream）
    public InputStream downloadFile(String objectName) throws Exception {
        GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
        return response;
    }

    public String objectNameFromUrl(String url) {
        int authority = url == null ? -1 : url.indexOf("://");
        int path = authority < 0 ? -1 : url.indexOf('/', authority + 3);
        String prefix = "/" + bucketName + "/";
        if (path < 0 || !url.startsWith(prefix, path) || url.length() == path + prefix.length()) {
            throw new IllegalArgumentException("无法解析文档存储地址");
        }
        return url.substring(path + prefix.length());
    }

    // 删除文件
    public void deleteFile(String objectName) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build());
    }

    // 生成临时下载链接（带签名，有效期 7 天）
    public String getPresignedUrl(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(7, TimeUnit.DAYS)
                        .build());
    }
}
