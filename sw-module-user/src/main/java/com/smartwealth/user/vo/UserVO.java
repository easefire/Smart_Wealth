package com.smartwealth.user.vo;

import com.smartwealth.user.entity.UserBase;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    // 1. 去掉 static，让属性属于对象实例
    private String username;
    private String phone;

    /**
     * 静态工厂方法：将 Entity 转换为 VO
     */
    public static UserVO fromEntity(UserBase user) {
        if (user == null) return null;

        UserVO vo = new UserVO();
        vo.setUsername(user.getUsername());

        // 手机号脱敏处理
        String rawPhone = user.getPhone();
        if (rawPhone != null && rawPhone.length() == 11) {
            // 使用正则：保留前3位和后4位，中间4位替换为 ****
            String maskedPhone = rawPhone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
            vo.setPhone(maskedPhone);
        } else {
            vo.setPhone(rawPhone);
        }

        return vo;
    }
}