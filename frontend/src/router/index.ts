import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import SettingsView from '../views/SettingsView.vue'
import ExternalFavoritesView from '../views/ExternalFavoritesView.vue'
import DedupView from '../views/DedupView.vue'
import UsersView from '../views/UsersView.vue'
import StatsView from '../views/StatsView.vue'
import CreatorsView from '../views/CreatorsView.vue'
import MangaRecommendView from '../views/MangaRecommendView.vue'
import Bd2SpineView from '../views/Bd2SpineView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView
    },
    {
      path: '/type/:mediaType',
      name: 'type-filter',
      component: HomeView,
      props: true
    },
    {
      path: '/settings',
      name: 'settings',
      component: SettingsView
    },
    {
      path: '/external',
      name: 'external-favorites',
      component: ExternalFavoritesView
    },
    {
      path: '/dedup',
      name: 'dedup',
      component: DedupView
    },
    {
      path: '/users',
      name: 'users',
      component: UsersView
    },
    {
      // Dashboard stats: chart-heavy overview, distribution, activity, attention.
      path: '/stats',
      name: 'stats',
      component: StatsView
    },
    {
      // Unified creators list (X authors + manga artists).
      path: '/creators',
      name: 'creators',
      component: CreatorsView
    },
    {
      // Creator detail (same view, branches on route.params.screenName).
      path: '/creators/:screenName',
      name: 'creator-detail',
      component: CreatorsView,
      props: true
    },
    {
      path: '/recommend',
      name: 'manga-recommend',
      component: MangaRecommendView
    },
    {
      path: '/bd2-spine',
      name: 'bd2-spine',
      component: Bd2SpineView
    }
  ]
})

export default router
