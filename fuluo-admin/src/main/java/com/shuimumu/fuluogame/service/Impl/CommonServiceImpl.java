package com.shuimumu.fuluogame.service.Impl;

import com.shuimumu.fuluogame.exception.BusinessException;
import com.shuimumu.fuluogame.service.CommonService;
import com.shuimumu.fuluogame.utils.AliOssUtil;
import com.shuimumu.fuluogame.utils.ContentModerationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
public class CommonServiceImpl implements CommonService {

    // P0 修复：required=false，SDK 未配置（aliyun.oss.enabled / aliyun.green.enabled != true）时字段为 null，
    // 上传时做显式判空，避免 NPE，并给出明确业务错误/告警。
    @Autowired(required = false)
    private AliOssUtil aliOssUtil;

    @Autowired(required = false)
    private ContentModerationUtil contentModerationUtil;

    @Override
    public String upload(MultipartFile file) {
        // P0 修复：OSS 是上传核心依赖，未配置时直接返回业务错误
        if (aliOssUtil == null) {
            throw new BusinessException("对象存储未配置，无法上传文件");
        }
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String objectName = UUID.randomUUID().toString() + suffix;
        try {
            String url = aliOssUtil.upload(file.getBytes(), objectName);
            // 图片审核：SDK 未配置时跳过并告警（best-effort），生产应配置 aliyun.green.enabled=true
            if (contentModerationUtil == null) {
                log.warn("图片审核SDK未配置，跳过图片审核 url={}", url);
                return url;
            }
            ContentModerationUtil.ModerationResult result = contentModerationUtil.moderateImage(url);
            if (!result.isPassed()) {
                aliOssUtil.delete(objectName);
                log.warn("图片审核不通过 已删除OSS url={} label={} desc={}", url, result.getLabel(), result.getDescription());
                throw new BusinessException("图片包含违规内容" +
                        (result.getDescription() != null ? "：" + result.getDescription() : ""));
            }
            log.info("文件上传成功，url：{}", url);
            return url;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }
}
