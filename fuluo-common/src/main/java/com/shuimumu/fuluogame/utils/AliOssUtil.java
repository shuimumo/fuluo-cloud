package com.shuimumu.fuluogame.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayInputStream;

@Data
@AllArgsConstructor
@Slf4j
public class AliOssUtil {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    /**
     * 文件上传
     *
     * @param bytes
     * @param objectName
     * @return
     */
    public String upload(byte[] bytes, String objectName) {

        // 创建OSSClient实例。
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes));
        } catch (OSSException oe) {
            log.error("OSS upload failed: ErrorCode={}, ErrorMessage={}, RequestId={}",
                    oe.getErrorCode(), oe.getErrorMessage(), oe.getRequestId());
            throw new RuntimeException("文件上传失败，请稍后重试", oe);
        } catch (ClientException ce) {
            log.error("OSS client error: {}", ce.getMessage());
            throw new RuntimeException("文件上传失败，请稍后重试", ce);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }

        String url = "https://" + bucketName + "." + endpoint + "/" + objectName;
        log.info("文件上传到:{}", url);
        return url;
    }

    public void delete(String objectName) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ossClient.deleteObject(bucketName, objectName);
        } finally {
            if (ossClient != null) ossClient.shutdown();
        }
    }
}
