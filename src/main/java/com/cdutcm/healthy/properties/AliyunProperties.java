package com.cdutcm.healthy.properties;

import lombok.Data;

/**
 * @Author :  涂元坤
 * @Mail : 766564616@qq.com
 * @Create : 2019/3/25 15:26 星期一
 * @Description :
 */
@Data
public class AliyunProperties {
    private String accessKeyId = "LTAInDjKhaewEuCk";
    private String secret = "F5l49vvr6nazVOJgaOvJAVQoJ9vsyc";
    private String signName = "涂元坤";

    private String verifyTemplateCode = "SMS_100830105";
    private String healthyTemplateCode = "SMS_160575192";
}
