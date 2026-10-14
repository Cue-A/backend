package com.cuea.domain.user.entity;

import com.cuea.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 마이페이지에 보이는 부가 정보. {@link User} 와 1:1 입니다.
 *
 * <p>로그인에 필요한 값({@code nickname}, {@code email})은 {@link User} 에 있고
 * 여기에는 없어도 서비스가 도는 값만 둡니다. 그래서 전 컬럼이 nullable 입니다.
 * 가입 시점에 만들지 않고 사용자가 처음 저장할 때 만들어도 됩니다.
 */
@Entity
@Table(name = "user_profile")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_profile_id")
    private Long userProfileId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(length = 500)
    private String introduction;

    /** 희망 직무. 세션의 {@code job_role} 기본값으로 씁니다. */
    @Column(name = "target_job_role", length = 50)
    private String targetJobRole;

    @Column(name = "career_level", length = 20)
    private String careerLevel;

    @Column(name = "school_name", length = 100)
    private String schoolName;

    @Column(length = 100)
    private String major;

    @Column(name = "graduation_year")
    private Integer graduationYear;
}
