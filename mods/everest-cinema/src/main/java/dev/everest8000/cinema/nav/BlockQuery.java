package dev.everest8000.cinema.nav;

/** What the path finder needs to know about one block. Implemented over the client world and by tests. */
public interface BlockQuery {
    /** Collision height of the block (0 = no collision, 1 = full block; slabs 0.5, snow layers n/8...). */
    double collisionTop(int x, int y, int z);

    /** Ladders, vines and other blocks you can climb. */
    boolean climbable(int x, int y, int z);

    /** Water, lava, fire, powder snow, cactus... anything a walker should never path into. */
    boolean hazard(int x, int y, int z);

    /** Loose snow at this foot position, 0..1 (layers/8, 1 for powder snow); slows walkers down. */
    double snowDepth(int x, int y, int z);

    /** False outside loaded chunks: the search treats those as walls. */
    boolean loaded(int x, int z);
}
