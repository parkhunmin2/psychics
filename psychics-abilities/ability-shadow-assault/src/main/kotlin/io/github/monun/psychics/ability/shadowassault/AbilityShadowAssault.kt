package io.github.monun.psychics.ability.shadowassault

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.hostileFilter
import io.github.legendshot414.tap.config.Name
import net.kyori.adventure.text.Component.text
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.math.acos
import kotlin.math.sin

/**
 * 섀도우 어썰트 능력
 *
 * 플레이어가 스킬을 사용하면:
 * 1. 전방 15블록 내의 적을 탐색
 * 2. 탐색된 적의 뒤쪽 1.5블록으로 순간이동
 * 3. 8 데미지를 적에게 입힘
 * 4. 엔더맨 순간이동 사운드 재생
 * 5. 크리티컬 입자 효과 생성
 *
 * 기본 쿨다운: 8초
 * 마나 소비: 15.0
 * 무기: 다이아몬드 검
 */
@Name("shadowassault")
class AbilityConceptShadowAssault : AbilityConcept() {
    init {
        // 재사용 대기시간 설정 (8초 = 8000밀리초)
        cooldownTime = 8000L

        // 능력의 효과 범위 (블록 단위)
        range = 15.0

        // 능력 사용시 소비되는 마나
        cost = 15.0

        // 대상에게 입힐 피해 설정
        // MELEE: 근접 공격 타입
        // ATTACK_DAMAGE: 공격력 기반 피해 계산, 8배수
        damage = Damage.of(DamageType.MELEE, EsperAttribute.ATTACK_DAMAGE to 8.0)

        // UI에 표시될 능력 설명
        description = listOf(
            text("전방 15블록 내의 적을 찾아 뒤로 순간이동하여 공격합니다.")
        )

        // 이 능력을 사용할 무기 지정
        wand = ItemStack(Material.DIAMOND_SWORD)
    }
}

/**
 * 섀도우 어썰트 능력 구현 클래스
 *
 * 플레이어의 우클릭 또는 좌클릭으로 발동되는 능력
 * 타겟팅 시스템으로 대상을 선정하고, 순간이동 후 공격을 수행
 */
class AbilityShadowAssault : ActiveAbility<AbilityConceptShadowAssault>(), Listener {
    companion object {
        // 대상 탐색 범위 (블록 단위)
        private const val TELEPORT_RANGE = 15.0

        // 순간이동 목표 위치 (대상 뒤쪽 거리, 블록 단위)
        private const val TELEPORT_DISTANCE = 1.5
    }

    /**
     * 능력 초기화 시 호출되는 메서드
     * 타겟팅 로직을 설정
     *
     * 우선순위:
     * 1. Ray trace를 사용한 직선상 대상 탐색 (가장 가까운 대상)
     * 2. 실패 시 범위 내 모든 적 중 첫 번째 대상 탐색
     */
    override fun onInitialize() {
        // 타겟팅 함수 정의
        // 이 람다식이 반환한 대상이 onCast에 전달됨
        targeter = {
            val player = esper.player
            val location = player.location
            val world = location.world

            // 플레이어의 눈 위치를 시작점으로 설정
            val startPos = player.eyeLocation

            // 플레이어가 바라보는 방향을 지정
            val direction = startPos.direction

            // 광선 추적(Ray trace)으로 가장 가까운 적 탐색
            // - 범위: concept.range (15블록)
            // - 액체는 무시 (FluidCollisionMode.NEVER)
            // - 플레이어 자신은 제외 (hostileFilter 사용)
            world.rayTrace(
                startPos,
                direction,
                concept.range,
                FluidCollisionMode.NEVER,
                true,
                0.1,
                player.hostileFilter()
            )?.hitEntity ?: run {
                // Ray trace에서 대상을 찾지 못한 경우 대체 방법 실행

                // 범위 내 모든 개체 탐색을 위한 경계 상자 생성
                val nearbyRadius = concept.range
                val bbox = location.apply { y += 1.0 }.clone().let { l ->
                    org.bukkit.util.BoundingBox.of(l, nearbyRadius, nearbyRadius, nearbyRadius)
                }

                // 경계 상자 내 적 엔티티들 필터링
                world.getNearbyEntities(bbox) { entity ->
                    // 세 가지 조건을 모두 만족하는 엔티티만 선별
                    entity != player &&  // 플레이어 자신 제외
                    entity is LivingEntity &&  // LivingEntity 타입만
                    player.hostileFilter().test(entity)  // 적 필터링
                }
                .firstOrNull() as? LivingEntity  // 첫 번째 적만 반환
            }
        }
    }

    /**
     * 능력 시전 시 호출되는 메서드
     * 순간이동, 사운드, 입자, 데미지 처리를 순차적으로 실행
     *
     * @param event: 플레이어 이벤트
     * @param action: 마우스 클릭 종류 (LEFT_CLICK 또는 RIGHT_CLICK)
     * @param target: 타겟팅으로 선정된 대상 (LivingEntity)
     */
    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        // 대상이 LivingEntity가 아니면 능력 중단
        if (target !is LivingEntity) return

        val player = esper.player

        // 대상의 현재 위치 획득
        val targetLocation = target.location

        // 플레이어에서 대상으로 향하는 방향 벡터 계산
        // subtract: 플레이어 위치를 뺌 (상대 위치 계산)
        // normalize: 길이를 1로 정규화 (방향만 남음)
        val direction = targetLocation.clone().subtract(player.location).toVector().normalize()

        // 순간이동 목표 위치 계산
        // 대상의 반대 방향으로 1.5블록 떨어진 위치
        val teleportLocation = targetLocation.clone().add(direction.multiply(-TELEPORT_DISTANCE))

        // Y(높이) 값은 대상의 높이와 동일하게 설정
        teleportLocation.y = targetLocation.y

        // ==================== 순간이동 실행 ====================
        player.teleport(teleportLocation)

        // ==================== 사운드 재생 ====================
        // 엔더맨의 순간이동 사운드로 신비로운 연출
        // 볼륨: 1.0F (정상 크기)
        // 피치: 1.0F (정상 음높이)
        val world = player.world
        world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F)

        // ==================== 입자 효과 생성 ====================
        // 플레이어 위 1블록 높이에 입자 생성
        val particleLocation = player.location.add(0.0, 1.0, 0.0)

        // 크리티컬 히트 입자 (보라색 별 모양)
        // - 개수: 15개
        // - 분산도: 0.5 (0.5블록 범위 내에서 무작위 분산)
        // - 속도: 0.3 (입자 이동 속도)
        world.spawnParticle(
            Particle.CRIT,
            particleLocation,
            15,
            0.5,
            0.5,
            0.5,
            0.3
        )

        // ==================== 데미지 적용 ====================
        // 대상에게 AbilityConcept에서 설정한 피해 적용 (8 데미지)
        // psychicDamage() 확장 함수는 능력 기반 피해 시스템 사용
        target.psychicDamage()

        // ==================== 마나 소비 및 쿨다운 설정 ====================
        // 마나 소비 처리 (AbilityConcept.cost = 15.0)
        psychic.consumeMana(concept.cost)

        // 쿨다운 설정 (8초)
        // 이 동안 플레이어는 이 능력을 다시 사용할 수 없음
        cooldownTime = concept.cooldownTime
    }
}

