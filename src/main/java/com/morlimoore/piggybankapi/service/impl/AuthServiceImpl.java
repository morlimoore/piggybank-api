package com.morlimoore.piggybankapi.service.impl;

import com.morlimoore.piggybankapi.dto.AuthResponseDto;
import com.morlimoore.piggybankapi.dto.LoginUserRequestDto;
import com.morlimoore.piggybankapi.dto.RegisterUserRequestDto;
import com.morlimoore.piggybankapi.entities.NotificationEmail;
import com.morlimoore.piggybankapi.entities.Token;
import com.morlimoore.piggybankapi.entities.User;
import com.morlimoore.piggybankapi.exceptions.CustomException;
import com.morlimoore.piggybankapi.payload.ApiResponse;
import com.morlimoore.piggybankapi.repositories.TokenRepository;
import com.morlimoore.piggybankapi.repositories.UserRepository;
import com.morlimoore.piggybankapi.security.JwtTokenProvider;
import com.morlimoore.piggybankapi.service.AuthService;
import com.morlimoore.piggybankapi.service.MailService;
import com.morlimoore.piggybankapi.service.SignupTokenService;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.Optional;

import static com.morlimoore.piggybankapi.util.CreateResponse.createResponse;
import static org.springframework.http.HttpStatus.*;

@Service
public class AuthServiceImpl implements AuthService {

    Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final ModelMapper modelMapper;
    private final PasswordEncoder passwordEncoder;
    private final SignupTokenService signupTokenService;
    private final TokenRepository tokenRepository;
    private final MailService mailService;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtProvider;

    public AuthServiceImpl(UserRepository userRepository,
                           ModelMapper modelMapper,
                           PasswordEncoder passwordEncoder,
                           SignupTokenService signupTokenService,
                           TokenRepository tokenRepository,
                           MailService mailService,
                           AuthenticationManager authenticationManager,
                           JwtTokenProvider jwtProvider) {
        this.userRepository = userRepository;
        this.modelMapper = modelMapper;
        this.passwordEncoder = passwordEncoder;
        this.signupTokenService = signupTokenService;
        this.tokenRepository = tokenRepository;
        this.mailService = mailService;
        this.authenticationManager = authenticationManager;
        this.jwtProvider = jwtProvider;
    }

    @Override
    @Transactional
    public ResponseEntity<ApiResponse<String>> signup(RegisterUserRequestDto registerUserRequestDto) {
        User user = modelMapper.map(registerUserRequestDto, User.class);
        user.setRole("USER");
        user.setPassword(passwordEncoder.encode(registerUserRequestDto.getPassword()));
        user.setIsEnabled(false);
        userRepository.save(user);
        User tempUser = userRepository.getUserByEmail(user.getEmail()).get();
        String signUpToken = signupTokenService.getToken();
        Token token = new Token();
        token.setToken(signUpToken);
        token.setPurpose("SIGNUP");
        token.setIsValid(true);
        token.setUser(tempUser);
        tokenRepository.save(token);
        mailService.sendMail(new NotificationEmail("Please activate your account",
                user.getEmail(), "Thank you for signing up to PiggyBank App, please click on " +
                "below url to activate your account : " +
                "http://localhost:8080/api/auth/accountVerification/" + token));
        ApiResponse<String> response = new ApiResponse<>(OK);
        response.setData("User Registration Successful");
        response.setMessage("Success");
        return createResponse(response);
    }

    @Override
    public ResponseEntity<Object> login(LoginUserRequestDto loginUserRequestDto) {
        Authentication authenticate = null;
        try {
            authenticate = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                    loginUserRequestDto.getEmail(),
                    loginUserRequestDto.getPassword()
            ));
        } catch (BadCredentialsException e) {
            ApiResponse<?> response = new ApiResponse<>(HttpStatus.UNAUTHORIZED);
            response.setError(e.getMessage());
            response.setMessage("Bad credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED.value()).body(response);
        }
        SecurityContextHolder.getContext().setAuthentication(authenticate);
        System.out.println("*AuthService* authenticate: " + authenticate.getName());
        String token = jwtProvider.createLoginToken(authenticate.getName());
        logger.info(token);
        AuthResponseDto authResponseDto = new AuthResponseDto(token, loginUserRequestDto.getEmail());
        ApiResponse<AuthResponseDto> response = new ApiResponse<>(OK);
        response.setData(authResponseDto);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ApiResponse<String>> verifyAccount(String token) {
        Optional<Token> verificationToken = tokenRepository.findByToken(token);
        verificationToken.orElseThrow(() -> new CustomException("Invalid token", BAD_REQUEST));
        if (verificationToken.get().getIsValid()) {
            fetchUserAndEnable(verificationToken.get());
            fetchTokenAndInvalidate(verificationToken.get().getToken());
        } else {
            throw new CustomException("Token has been used", BAD_REQUEST);
        }
        ApiResponse<String> response = new ApiResponse<>(OK);
        response.setData("Account activated successfully");
        response.setMessage("Success");
        return createResponse(response);
    }

    @Transactional
    public void fetchUserAndEnable(Token token) {
        String email = token.getUser().getEmail();
        User tempUser = userRepository.getUserByEmail(email).orElseThrow(() ->
                new CustomException("User not found with email - " + email, BAD_REQUEST));
        tempUser.setIsEnabled(true);
        userRepository.save(tempUser);
    }

    @Transactional
    public void fetchTokenAndInvalidate(String token) {
        Optional<Token> verificationToken = tokenRepository.findByToken(token);
        verificationToken.orElseThrow(() -> new CustomException("Invalid token", BAD_REQUEST));
        verificationToken.get().setIsValid(false);
        tokenRepository.save(verificationToken.get());
    }
}
