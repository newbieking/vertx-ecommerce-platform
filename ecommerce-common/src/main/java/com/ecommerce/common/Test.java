package com.ecommerce.common;

import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;

public class Test {

    public static void main(String[] args) {
        boolean matches = Pattern.compile("^\\Q/api/orders\\E(/.*)?$").matcher("/api/orders").matches();
        System.out.println("matches = " + matches);
    }
}
