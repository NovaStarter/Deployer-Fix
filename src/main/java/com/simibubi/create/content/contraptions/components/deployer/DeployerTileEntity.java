package com.simibubi.create.content.contraptions.components.deployer;

import com.simibubi.create.AllBlockPartials;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.base.KineticTileEntity;
import com.simibubi.create.content.curiosities.tools.SandPaperItem;
import com.simibubi.create.foundation.advancement.AllTriggers;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.tileEntity.TileEntityBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.utility.NBTHelper;
import com.simibubi.create.foundation.utility.VecHelper;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.util.math.RayTraceContext.BlockMode;
import net.minecraft.util.math.RayTraceContext.FluidMode;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;

import static com.simibubi.create.content.contraptions.base.DirectionalKineticBlock.FACING;

public class DeployerTileEntity extends KineticTileEntity {

	protected State state;
	protected Mode mode;
	protected ItemStack heldItem = ItemStack.EMPTY;
	protected DeployerFakePlayer player;
	protected int timer;
	protected float reach;
	protected boolean boop = false;
	protected List<ItemStack> overflowItems = new ArrayList<>();
	protected FilteringBehaviour filtering;
	protected boolean redstoneLocked;
	private LazyOptional<IItemHandlerModifiable> invHandler;
	private ListNBT deferredInventoryList;

	enum State {
		WAITING, EXPANDING, RETRACTING, DUMPING;
	}

	enum Mode {
		PUNCH, USE
	}

	public DeployerTileEntity(TileEntityType<? extends DeployerTileEntity> type) {
		super(type);
		state = State.WAITING;
		mode = Mode.USE;
		heldItem = ItemStack.EMPTY;
		redstoneLocked = false;
	}

	@Override
	public void addBehaviours(List<TileEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		filtering = new FilteringBehaviour(this, new DeployerFilterSlot());
		behaviours.add(filtering);
	}

	@Override
	public void initialize() {
		super.initialize();
		if (!world.isRemote) {
			player = new DeployerFakePlayer((ServerWorld) world);
			if (deferredInventoryList != null) {
				player.inventory.read(deferredInventoryList);
				deferredInventoryList = null;
				heldItem = player.getHeldItemMainhand();
				sendData();
			}
			Vec3d initialPos = VecHelper.getCenterOf(pos.offset(getBlockState().get(FACING)));
			player.setPosition(initialPos.x, initialPos.y, initialPos.z);
		}
		invHandler = LazyOptional.of(this::createHandler);
	}

	protected void onExtract(ItemStack stack) {
		player.setHeldItem(Hand.MAIN_HAND, stack.copy());
		sendData();
		markDirty();
	}

	protected int getTimerSpeed() {
		return (int) (getSpeed() == 0 ? 0 : MathHelper.clamp(Math.abs(getSpeed() * 2), 8, 512));
	}

	@Override
	public void tick() {
		super.tick();

		if (getSpeed() == 0)
			return;
		if (!world.isRemote && player != null && player.blockBreakingProgress != null) {
			if (world.isAirBlock(player.blockBreakingProgress.getKey())) {
				world.sendBlockBreakProgress(player.getEntityId(), player.blockBreakingProgress.getKey(), -1);
				player.blockBreakingProgress = null;
			}
		}
		if (timer > 0) {
			timer -= getTimerSpeed();
			return;
		}
		if (world.isRemote)
			return;

		ItemStack stack = player.getHeldItemMainhand();
		if (state == State.WAITING) {
			if (!overflowItems.isEmpty()) {
				timer = getTimerSpeed() * 10;
				return;
			}

			boolean changed = false;
			for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
				if (overflowItems.size() > 10)
					break;
				ItemStack item = player.inventory.getStackInSlot(i);
				if (item.isEmpty())
					continue;
				if (item != stack || !filtering.test(item)) {
					overflowItems.add(item);
					player.inventory.setInventorySlotContents(i, ItemStack.EMPTY);
					changed = true;
				}
			}

			if (changed) {
				sendData();
				timer = getTimerSpeed() * 10;
				return;
			}

			Direction facing = getBlockState().get(FACING);
			if (mode == Mode.USE && !DeployerHandler.shouldActivate(stack, world, pos.offset(facing, 2))) {
				timer = getTimerSpeed() * 10;
				return;
			}

			// Check for advancement conditions
			if (mode == Mode.PUNCH && !boop && startBoop(facing))
				return;

			if (redstoneLocked)
				return;

			state = State.EXPANDING;
			Vec3d movementVector = getMovementVector();
			Vec3d rayOrigin = VecHelper.getCenterOf(pos)
				.add(movementVector.scale(3 / 2f));
			Vec3d rayTarget = VecHelper.getCenterOf(pos)
				.add(movementVector.scale(5 / 2f));
			RayTraceContext rayTraceContext =
				new RayTraceContext(rayOrigin, rayTarget, BlockMode.OUTLINE, FluidMode.NONE, player);
			BlockRayTraceResult result = world.rayTraceBlocks(rayTraceContext);
			reach = (float) (.5f + Math.min(result.getHitVec()
				.subtract(rayOrigin)
				.length(), .75f));

			timer = 1000;
			sendData();
			return;
		}

		if (state == State.EXPANDING) {
			if (boop)
				triggerBoop();
			else
				activate();

			state = State.RETRACTING;
			timer = 1000;
			sendData();
			return;
		}

		if (state == State.RETRACTING) {
			state = State.WAITING;
			timer = 500;
			sendData();
			return;
		}

	}

	public boolean startBoop(Direction facing) {
		if (!world.isAirBlock(pos.offset(facing, 1)) || !world.isAirBlock(pos.offset(facing, 2)))
			return false;
		BlockPos otherDeployer = pos.offset(facing, 4);
		if (!world.isBlockPresent(otherDeployer))
			return false;
		TileEntity otherTile = world.getTileEntity(otherDeployer);
		if (!(otherTile instanceof DeployerTileEntity))
			return false;
		DeployerTileEntity deployerTile = (DeployerTileEntity) otherTile;
		if (world.getBlockState(otherDeployer)
			.get(FACING)
			.getOpposite() != facing || deployerTile.mode != Mode.PUNCH)
			return false;

		boop = true;
		reach = 1f;
		timer = 1000;
		state = State.EXPANDING;
		sendData();
		return true;
	}

	public void triggerBoop() {
		TileEntity otherTile = world.getTileEntity(pos.offset(getBlockState().get(FACING), 4));
		if (!(otherTile instanceof DeployerTileEntity))
			return;

		DeployerTileEntity deployerTile = (DeployerTileEntity) otherTile;
		if (!deployerTile.boop || deployerTile.state != State.EXPANDING)
			return;
		if (deployerTile.timer > 0)
			return;

		// everything should be met
		boop = false;
		deployerTile.boop = false;
		deployerTile.state = State.RETRACTING;
		deployerTile.timer = 1000;
		deployerTile.sendData();

		// award nearby players
		List<ServerPlayerEntity> players =
			world.getEntitiesWithinAABB(ServerPlayerEntity.class, new AxisAlignedBB(pos).grow(9));
		players.forEach(AllTriggers.DEPLOYER_BOOP::trigger);
	}

	protected void activate() {
		Vec3d movementVector = getMovementVector();
		Direction direction = getBlockState().get(FACING);
		Vec3d center = VecHelper.getCenterOf(pos);
		BlockPos clickedPos = pos.offset(direction, 2);
		player.rotationYaw = direction.getHorizontalAngle();
		player.rotationPitch = direction == Direction.UP ? -90 : direction == Direction.DOWN ? 90 : 0;

		DeployerHandler.activate(player, center, clickedPos, movementVector, mode);
		if (player != null)
			heldItem = player.getHeldItemMainhand();
	}

	protected Vec3d getMovementVector() {
		if (!AllBlocks.DEPLOYER.has(getBlockState()))
			return Vec3d.ZERO;
		return new Vec3d(getBlockState().get(FACING)
			.getDirectionVec());
	}

	@Override
	protected void read(CompoundNBT compound, boolean clientPacket) {
		state = NBTHelper.readEnum(compound, "State", State.class);
		mode = NBTHelper.readEnum(compound, "Mode", Mode.class);
		timer = compound.getInt("Timer");
		redstoneLocked = compound.getBoolean("Powered");

		deferredInventoryList = compound.getList("Inventory", NBT.TAG_COMPOUND);
		overflowItems = NBTHelper.readItemList(compound.getList("Overflow", NBT.TAG_COMPOUND));
		if (compound.contains("HeldItem"))
			heldItem = ItemStack.read(compound.getCompound("HeldItem"));
		super.read(compound, clientPacket);

		if (!clientPacket)
			return;
		reach = compound.getFloat("Reach");
		if (compound.contains("Particle")) {
			ItemStack particleStack = ItemStack.read(compound.getCompound("Particle"));
			SandPaperItem.spawnParticles(VecHelper.getCenterOf(pos)
				.add(getMovementVector().scale(2f)), particleStack, this.world);
		}
	}

	@Override
	public void write(CompoundNBT compound, boolean clientPacket) {
		NBTHelper.writeEnum(compound, "Mode", mode);
		NBTHelper.writeEnum(compound, "State", state);
		compound.putInt("Timer", timer);
		compound.putBoolean("Powered", redstoneLocked);

		if (player != null) {
			compound.put("HeldItem", player.getHeldItemMainhand()
				.serializeNBT());
			ListNBT invNBT = new ListNBT();
			player.inventory.write(invNBT);
			compound.put("Inventory", invNBT);
			compound.put("Overflow", NBTHelper.writeItemList(overflowItems));
		}

		super.write(compound, clientPacket);

		if (!clientPacket)
			return;
		compound.putFloat("Reach", reach);
		if (player == null)
			return;
		compound.put("HeldItem", player.getHeldItemMainhand()
			.serializeNBT());
		if (player.spawnedItemEffects != null) {
			compound.put("Particle", player.spawnedItemEffects.serializeNBT());
			player.spawnedItemEffects = null;
		}
	}

	private IItemHandlerModifiable createHandler() {
		return new DeployerItemHandler(this);
	}

	public void redstoneUpdate() {
		if (world.isRemote)
			return;
		boolean blockPowered = world.isBlockPowered(pos);
		if (blockPowered == redstoneLocked)
			return;
		redstoneLocked = blockPowered;
		sendData();
	}

	@Override
	public boolean hasFastRenderer() {
		return false;
	}

	public AllBlockPartials getHandPose() {
		return mode == Mode.PUNCH ? AllBlockPartials.DEPLOYER_HAND_PUNCHING
			: heldItem.isEmpty() ? AllBlockPartials.DEPLOYER_HAND_POINTING : AllBlockPartials.DEPLOYER_HAND_HOLDING;
	}

	@Override
	public AxisAlignedBB makeRenderBoundingBox() {
		return super.makeRenderBoundingBox().grow(3);
	}

	@Override
	public void remove() {
		super.remove();
		if (invHandler != null)
			invHandler.invalidate();
	}

	public void changeMode() {
		mode = mode == Mode.PUNCH ? Mode.USE : Mode.PUNCH;
		markDirty();
		sendData();
	}

	@Override
	public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
		if (isItemHandlerCap(cap) && invHandler != null) 
			return invHandler.cast();
		return super.getCapability(cap, side);
	}
	
	@Override
	public boolean addToTooltip(List<String> tooltip, boolean isPlayerSneaking) {
		if (super.addToTooltip(tooltip, isPlayerSneaking))
			return true;
		if (getSpeed() == 0)
			return false;
		if (overflowItems.isEmpty())
			return false;
		TooltipHelper.addHint(tooltip, "hint.full_deployer");
		return true;
	}

	@Override
	public boolean shouldRenderAsTE() {
		return true;
	}
}
