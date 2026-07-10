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

    @Autowired
    private AliOssUtil aliOssUtil;

    @Autowired
    private ContentModerationUtil contentModerationUtil;

    @Override
    public String upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String objectName = UUID.randomUUID().toString() + suffix;
        try {
            String url = aliOssUtil.upload(file.getBytes(), objectName);
            // 图片审核
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
