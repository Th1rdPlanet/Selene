package ru.moonlightproject.dev.selene.paper;

import java.util.Set;

final class AuthInputValidation {
 record ValidationResult(boolean valid,String messageKey){
  static ValidationResult success(){return new ValidationResult(true,"");}
  static ValidationResult error(String messageKey){return new ValidationResult(false,messageKey);}
 }

 private static final Set<String> USER_STATUSES=Set.of("INVALID_REQUEST","EXPIRED","UNAVAILABLE");

 private AuthInputValidation(){}

 static ValidationResult password(String value,int minimum,int maximum){
  if(value.length()<minimum)return ValidationResult.error("password-too-short");
  if(value.length()>maximum)return ValidationResult.error("password-too-long");
  return ValidationResult.success();
 }

 static ValidationResult registration(String password,String confirmation,int minimum,int maximum){
  ValidationResult passwordResult=password(password,minimum,maximum);
  if(!passwordResult.valid())return passwordResult;
  return password.equals(confirmation)?ValidationResult.success():ValidationResult.error("password-mismatch");
 }

 static ValidationResult captcha(String value){
  return value.matches("[0-9]{5}")?ValidationResult.success():ValidationResult.error("captcha-format");
 }

 static String messageKeyForStatus(String status){return USER_STATUSES.contains(status)?status:null;}
}
