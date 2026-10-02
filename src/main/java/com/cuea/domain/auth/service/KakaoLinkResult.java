package com.cuea.domain.auth.service;

import com.cuea.domain.user.entity.User;

record KakaoLinkResult(User user, boolean isNewUser) {
}
