package ru.moonlightproject.dev.selene.core;

public record AdminRequest(String op,String username,int page,int pageSize){
 public AdminRequest{op=op==null?"":op;username=username==null?"":username;if(page<0||pageSize<0||pageSize>50)throw new IllegalArgumentException("Invalid admin pagination");}
}
