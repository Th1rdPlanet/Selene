package ru.moonlightproject.dev.selene.paper.interactive;

import java.util.List;

public record ArenaPlan(int width,int length,List<BlockPlacement> floor,ScenarioType scenario,
    Point target,List<Point> decoys,String targetMaterial,String challengeItem) {
 public ArenaPlan { floor=List.copyOf(floor);decoys=List.copyOf(decoys); }
 public record Point(int x,int y,int z){}
 public record BlockPlacement(Point point,String material){}
}
