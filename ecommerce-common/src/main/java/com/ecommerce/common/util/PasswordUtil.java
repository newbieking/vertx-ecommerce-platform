package com.ecommerce.common.util;

import org.mindrot.jbcrypt.BCrypt;

public class PasswordUtil {

    // BCrypt 自动生成 salt，work factor = 12
    public static String hash(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
    }

    public static boolean verify(String plainPassword, String hashedPassword) {
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }
}