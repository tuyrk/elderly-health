package com.cdutcm.healthy.service.impl;

import com.cdutcm.healthy.constant.RedisConstant;
import com.cdutcm.healthy.constant.WxMapMsgCategory;
import com.cdutcm.healthy.constant.WxMpReturnMsg;
import com.cdutcm.healthy.dao.FamilyDao;
import com.cdutcm.healthy.dao.UserDao;
import com.cdutcm.healthy.dataobject.entity.Family;
import com.cdutcm.healthy.dataobject.vo.user.BodyIndexVO;
import com.cdutcm.healthy.dataobject.wechat.AccessToken;
import com.cdutcm.healthy.enums.RecordTypeEnum;
import com.cdutcm.healthy.enums.ResultEnum;
import com.cdutcm.healthy.exception.HealthyException;
import com.cdutcm.healthy.properties.HealthyProperties;
import com.cdutcm.healthy.service.*;
import com.cdutcm.healthy.utils.Base64Util;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.bean.menu.WxMenu;
import me.chanjar.weixin.common.bean.menu.WxMenuButton;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateData;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.Cookie;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author : 涂元坤
 * @Mail : 766564616@qq.com
 * @Create : 2019/3/14 17:46 星期四
 * @Description :
 */
@Slf4j
@Service
public class WeChatMpServiceImpl implements WeChatMpService {

    @Autowired
    private FamilyDao familyDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private WxMpService wxMpService;
    @Autowired
    private AmapService amapService;
    @Autowired
    private PressureService pressureService;
    @Autowired
    private SugarService sugarService;
    @Autowired
    private ObesityService obesityService;
    @Autowired
    private UserService userService;

    @Autowired
    private RedisOperator redisOperator;

    @Autowired
    private HealthyProperties healthyProperties;

    private static Pattern NUMBER_PATTERN = Pattern.compile("\\d+(\\.\\d+)?");

    private static Pattern RECORD_TYPE_PATTERN = Pattern.compile("高压|低压|血糖|体重");

    private static Pattern PHONE_PATTERN = Pattern.compile("^(13\\d|14[579]|15[^4\\D]|17[^49\\D]|18\\d)\\d{8}$");

    @Override
    public AccessToken getAccessToken() {
        try {
            return new Gson().fromJson(wxMpService.getAccessToken(), AccessToken.class);
        } catch (Exception e) {
            log.error("【获取AccessToken】获取AccessToken错误");
            throw new HealthyException(ResultEnum.WECHAT_MP_GET_ACCESSTOKEN_ERROR);
        }
    }

    @Override
    public String wechatMpDispatcher(WxMpXmlMessage wxMpXmlMessage) {
        //从wxMpXmlMessage获取参数
        String msgType = wxMpXmlMessage.getMsgType();

        if (WxConsts.XmlMsgType.TEXT.equals(msgType) || WxConsts.XmlMsgType.VOICE.equals(msgType)) {//文本/语音消息
            return dealTextOrVoice(wxMpXmlMessage);
        } else if (WxConsts.XmlMsgType.EVENT.equals(msgType)) {//事件消息
            String event = wxMpXmlMessage.getEvent();
            if (WxConsts.EventType.SUBSCRIBE.equals(event)) {// 关注事件
                return dealSubscribeEvent(wxMpXmlMessage);
            } else if (WxConsts.EventType.LOCATION.equals(event)) {// 进入公众号，地理位置
                dealLocationEvent(wxMpXmlMessage);
            } else if (WxConsts.EventType.SCANCODE_WAITMSG.equals(event)) { // 扫码事件
                return dealScancodeWaitmsg(wxMpXmlMessage);
            } else if (WxConsts.EventType.CLICK.equals(event)) {//CLICK点击事件（记录血压、血糖、体重）
                return null;
            }
        } else if (WxConsts.XmlMsgType.LOCATION.equals(msgType)) {// 用户发送地理位置消息
            System.out.println("WxConsts.XmlMsgType.LOCATION");
        }
        return null;
    }

    @Override
    public void sendWxMpTempMsg(BodyIndexVO bodyIndexVO) throws WxErrorException {
        /*
        {{first.DATA}}
        微信昵称：{{keyword1.DATA}}
        位置信息：{{keyword2.DATA}}
        风险类型：{{keyword3.DATA}}
        风险级别：{{keyword4.DATA}}
        立即呼叫：{{keyword5.DATA}}
        {{remark.DATA}}
         */
        // 拼装模板消息数据信息
        List<WxMpTemplateData> data = Arrays.asList(
                new WxMpTemplateData("first", "您的亲属存在身体指标异常！\n"),
                new WxMpTemplateData("keyword1", bodyIndexVO.getNickname(), "#0000FF"),
                new WxMpTemplateData("keyword2", bodyIndexVO.getLocation(), "#0000FF"),
                new WxMpTemplateData("keyword3", bodyIndexVO.getType(), "#EE0000"),
                new WxMpTemplateData("keyword4", bodyIndexVO.getLevel(), "#EE0000"),
                // https://blog.csdn.net/cheekis/article/details/41869953 Html5 JS 拨打电话功能
                new WxMpTemplateData("keyword5", bodyIndexVO.getPhone().concat("\n"), "#0000FF"),
                new WxMpTemplateData("remark", "请及时联系亲属！")
        );
        WxMpTemplateMessage templateMessage = new WxMpTemplateMessage();
        templateMessage.setTemplateId(healthyProperties.getWechat().getTemplateId().get("bodyIndex"));// 模板ID
        templateMessage.setData(data);//模板消息
        templateMessage.setUrl(healthyProperties.getUrl().getHealthy().concat("/call?phone=").concat(bodyIndexVO.getPhone()));//详情
        List<String> openidList = bodyIndexVO.getOpenid();
        for (String openid : openidList) { // 循环家属openid列表
            templateMessage.setToUser(openid);// 发送到对方的Openid
            wxMpService.getTemplateMsgService().sendTemplateMsg(templateMessage);
        }
    }

    /**
     * 处理文本/语音事件
     */
    @Transactional(rollbackFor = Exception.class)
    public String dealTextOrVoice(WxMpXmlMessage wxMpXmlMessage) {
        //从语音或是文本中获取信息
        String content = wxMpXmlMessage.getContent();
        content = content != null ? content : wxMpXmlMessage.getRecognition();
        content = content.replace("。", "");
        String msg = "";
        if (Pattern.compile(WxMapMsgCategory.RECORD_INDEX).matcher(content).find()) { // 记录身体指标
            msg = recordIndex(wxMpXmlMessage, content);
        } else if (content.startsWith(WxMapMsgCategory.BIND_FAMILY)) { // 绑定家属
            msg = bindFamily(wxMpXmlMessage, content);
        } else if (content.startsWith(WxMapMsgCategory.UNTIED_FAMILY)) { // 解绑家属
            msg = untiedFamily(wxMpXmlMessage, content);
        } else if (WxMapMsgCategory.LIST_FAMILY.equals(content)) { // 家属列表
            msg = listFamily(wxMpXmlMessage.getFromUser());
        } else if (WxMapMsgCategory.GET_KEY.equals(content.toLowerCase())) { // 获取OPEN_KEY
            msg = Base64Util.encode(wxMpXmlMessage.getFromUser());//获取发送用户的Openid，然后加密作为key
        } else if (WxMapMsgCategory.HELP.equals(content)) { // 帮助菜单
            msg = helpMenu();
        }
        return buildWxMpXmlOutMessage(wxMpXmlMessage, msg);
    }

    //文本或者语音录入指标信息
    private String recordIndex(WxMpXmlMessage wxMpXmlMessage, String content) {
        boolean flag = false;
        //将用户发送的内容提取到map集合
        Map<String, Double> map = new HashMap<>(2);
        //匹配数字（包含小数点的数字）

        Matcher valMatcher = NUMBER_PATTERN.matcher(content);
        Matcher keyMatcher = RECORD_TYPE_PATTERN.matcher(content);
        while (valMatcher.find() && keyMatcher.find()) {
            map.put(keyMatcher.group(), Double.valueOf(valMatcher.group()));
        }

        //对map集合中的数据进行记录
        try {
            //记录血压
            if (map.containsKey("高压") || map.containsKey("低压")) {
                if (map.containsKey("高压") && map.containsKey("低压")) {
                    flag = pressureService.recordPressure(wxMpXmlMessage.getCreateTime(),
                            map.get("高压"), map.get("低压"), wxMpXmlMessage.getFromUser());
                } else {// 收缩压和舒张压只存在一种的情况下，提示用户同时输入收缩压舒张压
                    return WxMpReturnMsg.HIGH_LOW_PRESSURE_NOT_EXISTS;
                }
            }
            //记录血糖
            if (map.containsKey("血糖")) {
                flag = sugarService.recordSugar(wxMpXmlMessage.getCreateTime(),
                        map.get("血糖"), wxMpXmlMessage.getFromUser());
            }
            //记录体重
            if (map.containsKey("体重")) {
                flag = obesityService.recordObesity(wxMpXmlMessage.getCreateTime(),
                        map.get("体重"), wxMpXmlMessage.getFromUser());
            }
        } catch (Exception e) {
            log.error("【微信记录数据】失败，wxMpXmlMessage = {}", wxMpXmlMessage);
            return WxMpReturnMsg.RECORD_ERROR;
        }
        return flag ? WxMpReturnMsg.RECORD_SUCCESS : WxMpReturnMsg.RECORD_ERROR;
    }

    /**
     * 绑定家属电话号码
     *
     * @param wxMpXmlMessage 微信XML消息
     * @param content        家属电话号码
     * @return 成功或失败信息
     */
    private String bindFamily(WxMpXmlMessage wxMpXmlMessage, String content) {
        // 用户openid
        String uOpenid = wxMpXmlMessage.getFromUser();
        // 获取家属手机号码
        String phone = content.replace(WxMapMsgCategory.BIND_FAMILY, "");

        // 手机号码正则表达式验证
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return WxMpReturnMsg.PHONE_ERROR;
        }
        // 判断该家属是否关注微信公众号
        //家属openid
        String fOpenid = userDao.selectOpenidByPhone(phone);
        if (fOpenid == null) {
            return WxMpReturnMsg.FAMILY_NOT_SUBSCRIBE;
        }

        // 判断是否已经绑定
        if (familyDao.select(uOpenid, phone, fOpenid) != null) {
            return WxMpReturnMsg.FAMILY_EXISTS;
        }

        // 向数据库插入数据
        if (familyDao.insert(uOpenid, phone, fOpenid)) {
            return WxMpReturnMsg.RECORD_SUCCESS;
        }
        // 插入失败返回异常信息
        return WxMpReturnMsg.RECORD_ERROR;
    }

    /**
     * 解绑家属电话号码
     *
     * @param wxMpXmlMessage
     * @param content 家属电话号码
     * @return 成功或失败信息
     */
    private String untiedFamily(WxMpXmlMessage wxMpXmlMessage, String content) {
        String openid = wxMpXmlMessage.getFromUser();
        String phone = content.replace(WxMapMsgCategory.UNTIED_FAMILY, "");
        if (familyDao.delete(openid, phone)) {
            return WxMpReturnMsg.DELETE_SUCCESS;
        }
        // 删除失败返回异常信息
        return WxMpReturnMsg.DELETE_ERROR;
    }

    /**
     * 获取家属电话号码列表
     *
     * @param uOpenid
     * @return
     */
    private String listFamily(String uOpenid) {
        // 测试家属列表功能。
        StringBuilder msg = new StringBuilder();
        List<Family> familyList = familyDao.select(uOpenid);
        msg.append("您绑定的家属如下：");
        for (int i = 0; i < familyList.size(); i++) {
            msg.append("\n\n").append(i + 1).append("：").append(familyList.get(i).getPhone());
        }
        return msg.toString();
    }

    /**
     * 处理关注事件
     *
     * @param wxMpXmlMessage
     * @return 关注成功欢迎信息
     */
    private String dealSubscribeEvent(WxMpXmlMessage wxMpXmlMessage) {

        // 微信表情：http://bj.96weixin.com/emoji/
        String openid = wxMpXmlMessage.getFromUser();
        // 先查询数据库是否已经有记录，没有记录才新增用户
        // 当用户关注后，又不再关注。此时并不删除用户表中用户的数据
        if (userDao.selectByOpenid(openid) == null) {
            // 当用户关注的时候就在数据库用户表添加一条用户信息。
            userService.addUser(openid);
        }
        return buildWxMpXmlOutMessage(wxMpXmlMessage, "欢迎关注老人健康管理系统微信公众号\uD83D\uDE18\uD83D\uDE18\n\n"
                .concat("您能够记录身体指标、浏览推文、冠心病评估...\uD83D\uDE0C\n\n")
                .concat("回复“帮助”可以查看更多内容！\uD83D\uDE0F"));
    }

    /**
     * 处理位置事件
     */
    private void dealLocationEvent(WxMpXmlMessage wxMpXmlMessage) {
    /*
    <xml>
      <ToUserName><![CDATA[toUser]]></ToUserName>
      <FromUserName><![CDATA[fromUser]]></FromUserName>
      <CreateTime>123456789</CreateTime>
      <MsgType><![CDATA[event]]></MsgType>
      <Event><![CDATA[LOCATION]]></Event>
      <Latitude>23.137466</Latitude>
      <Longitude>113.352425</Longitude>
      <Precision>119.385040</Precision>
    </xml>
     */
        // 当用户进入公众高就记录用户当前所在位置。用户短信通知家属位置。。
        // 获取经纬度
        String location = wxMpXmlMessage.getLongitude() + "," + wxMpXmlMessage.getLatitude();
        // 获取用户微信openid
        String openid = wxMpXmlMessage.getFromUser();
        // 将用户位置记录在Redis缓存中。过期时间：30m
        redisOperator.set(String.format(RedisConstant.USER_LOCATION, openid), // 用户标识
                amapService.regeo(location), // 地理位置（经过逆地理编码后的位置描述）
                1800);// 过期时间：1800s=30m
    }

    /**
     * 处理 SCANCODE_WAITMSG 扫码事件
     */
    private String dealScancodeWaitmsg(WxMpXmlMessage wxMpXmlMessage) {
        RestTemplate restTemplate = new RestTemplate();
        String url = wxMpXmlMessage.getScanCodeInfo().getScanResult();
        if (url.contains("/scan/login/setOpenid/")) { //如果是登录链接
            String openid = wxMpXmlMessage.getFromUser();
            try {
                Map result = restTemplate.getForObject(url.concat(openid), Map.class);// 登录成功返回token
                if (result == null) {
                    return buildWxMpXmlOutMessage(wxMpXmlMessage, WxMpReturnMsg.AUTH_ERROR);
                }
                redisOperator.set(String.format(RedisConstant.TOKEN_ADMIN, result.get("token")), // token
                        result.get("openid").toString(), // 管理员openid
                        RedisConstant.EXPIRE);// 超时时间
                return buildWxMpXmlOutMessage(wxMpXmlMessage, WxMpReturnMsg.LOGIN_SUCCESS);
            } catch (Exception e) {
                return buildWxMpXmlOutMessage(wxMpXmlMessage, WxMpReturnMsg.LOGIN_ERROR);
            }
        }
        return buildWxMpXmlOutMessage(wxMpXmlMessage, WxMpReturnMsg.IDENT_INFO_ERROR);
    }

    /**
     * 帮助菜单
     * @return
     */
    private String helpMenu() {
        return "回复或发送语音“高压120低压86”即可记录血压\n\n"
                .concat("回复或发送语音“血糖5.6”即可记录血糖\n\n")
                .concat("回复或发送语音“体重60”即可记录体重\n\n")
                .concat("回复或发送语音“绑定家属18382471393”即可绑定家属\n\n")
                .concat("回复或发送语音“解绑家属18382471393”即可解绑家属\n\n");
    }

    /**
     * 创建菜单
     *
     * @return
     */
    @Override
    public boolean createMenu() {
        WxMenu wxMenu = new WxMenu();
        List<WxMenuButton> wxMenuButtonList = new ArrayList<>();

        WxMenuButton button1 = new WxMenuButton();
        button1.setName("记录信息");
        List<WxMenuButton> button1SubButtons = new ArrayList<>();
        WxMenuButton button11 = new WxMenuButton();
        button11.setName("记录收缩压");
        button11.setType(WxConsts.MenuButtonType.CLICK);
        button11.setKey(RecordTypeEnum.HIGH_PRESSURE.toString());
        WxMenuButton button12 = new WxMenuButton();
        button12.setName("记录舒张压");
        button12.setType(WxConsts.MenuButtonType.CLICK);
        button12.setKey(RecordTypeEnum.LOW_PRESSURE.toString());
        WxMenuButton button13 = new WxMenuButton();
        button13.setName("记录血糖");
        button13.setType(WxConsts.MenuButtonType.CLICK);
        button13.setKey(RecordTypeEnum.SUGAR.toString());
        WxMenuButton button14 = new WxMenuButton();
        button14.setName("记录体重");
        button14.setType(WxConsts.MenuButtonType.CLICK);
        button14.setKey(RecordTypeEnum.OBESITY.toString());
        Collections.addAll(button1SubButtons, button11, button12, button13, button14);
        button1.setSubButtons(button1SubButtons);

        WxMenuButton button2 = new WxMenuButton();
        button2.setName("扫码事件");
        button1SubButtons = new ArrayList<>();
        WxMenuButton button21 = new WxMenuButton();
        button21.setName("SCANCODE_PUSH");
        button21.setType(WxConsts.MenuButtonType.SCANCODE_PUSH);
        button21.setKey("21");
        WxMenuButton button22 = new WxMenuButton();
        button22.setName("SCANCODE_WAITMSG");
        button22.setType(WxConsts.MenuButtonType.SCANCODE_WAITMSG);
        button22.setKey("22");
        Collections.addAll(button1SubButtons, button21, button22);
        button2.setSubButtons(button1SubButtons);

        Collections.addAll(wxMenuButtonList, button1, button2);
        wxMenu.setButtons(wxMenuButtonList);
        try {
            wxMpService.getMenuService().menuCreate(wxMenu);
        } catch (Exception e) {
            log.error("【创建菜单】微信公众号创建菜单错误");
            throw new HealthyException(ResultEnum.WECHAT_MP_CREATE_MENU_ERROR);
        }
        return true;
    }

    public String buildWxMpXmlOutMessage(WxMpXmlMessage wxMpXmlMessage, String content) {
        return WxMpXmlOutMessage.TEXT()
                .content(content)
                .fromUser(wxMpXmlMessage.getToUser()).toUser(wxMpXmlMessage.getFromUser()).build().toXml();
    }
}
