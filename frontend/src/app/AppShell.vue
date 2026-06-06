<template>
  <div class="app-shell">
    <RouterView v-slot="{ Component, route }">
      <Transition name="app-fade" mode="out-in">
        <component :is="Component" :key="route.fullPath" class="app-shell__view" />
      </Transition>
    </RouterView>
  </div>
</template>
