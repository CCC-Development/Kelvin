package com.mrcrayfish.vehicle.block;


import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Author: MrCrayfish
 */
@SuppressWarnings("deprecation")
public abstract class RotatedObjectBlock extends ObjectBlock
{
    /**
     * Avoid {@link net.minecraft.world.level.block.state.properties.BlockStateProperties#HORIZONTAL_FACING} (field
     * missing) and every {@code DirectionProperty.create(...)} overload, since Connector-remapped runtimes can omit
     * those factory methods. The protected constructor is stable and still restricts this property to NESW.
     */
    public static final DirectionProperty DIRECTION =
            new DirectionProperty(
                    "facing", List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {};

    public RotatedObjectBlock(Properties properties)
    {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any().setValue(DIRECTION, Direction.NORTH));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(@NotNull BlockPlaceContext ctx)
    {
        BlockState state = this.defaultBlockState();

        return state.setValue(DIRECTION, ctx.getHorizontalDirection());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.@NotNull Builder<Block, BlockState> builder)
    {
        super.createBlockStateDefinition(builder);
        builder.add(DIRECTION);
    }

    @Override
    @NotNull
    public BlockState rotate(BlockState state, Rotation rotation)
    {
        return state.setValue(DIRECTION, rotation.rotate(state.getValue(DIRECTION)));
    }

    @Override
    @NotNull
    public BlockState mirror(BlockState state, Mirror mirror)
    {
        return state.rotate(mirror.getRotation(state.getValue(DIRECTION)));
    }

}
