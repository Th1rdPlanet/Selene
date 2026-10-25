package ru.moonlightproject.dev.selene.paper.interactive;

import java.util.List;

/**
 * Floor holds the island: its surface at y=0 and the tapering underside below it.
 * Scenery is purely decorative and never counts as an answer. {@code front} is the unit
 * step from the target towards the spawn: chests, stands and wall fixtures face the player.
 */
public record ArenaPlan(int width,int length,List<BlockPlacement> floor,List<BlockPlacement> scenery,List<Point> boundary,
    ScenarioType scenario,Point target,Point front,List<Point> decoys,String targetMaterial,String challengeItem) {
 public ArenaPlan { floor=List.copyOf(floor);scenery=List.copyOf(scenery);boundary=List.copyOf(boundary);decoys=List.copyOf(decoys); }
 /** Cell the player appears on, always land and kept clear. */
 public Point spawn(){return new Point(width/2,1,length/2);}
 /** Air cell on the front side of the target post that holds a button, lever or item frame. */
 public Point fixture(){return new Point(target.x()+front.x(),target.y()+1,target.z()+front.z());}
 public record Point(int x,int y,int z){}
 public record BlockPlacement(Point point,String material){}
}
