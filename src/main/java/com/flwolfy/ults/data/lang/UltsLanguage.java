package com.flwolfy.ults.data.lang;

import java.util.Map;

record UltsLanguage(String name, Map<String, String> translations) {
  UltsLanguage {
    translations = Map.copyOf(translations);
  }
}
