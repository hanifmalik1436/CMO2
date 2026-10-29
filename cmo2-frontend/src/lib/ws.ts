import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { sessionStore } from '../stores/session'

const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/**
 * Opens one authenticated STOMP-over-SockJS connection and subscribes to a topic.
 * The JWT is sent in the CONNECT frame's `Authorization` header; the backend's
 * `WsAuthChannelInterceptor` authenticates it and enforces that you may only
 * subscribe to your own tenant topic.
 *
 * Returns a disposer that unsubscribes and closes the socket.
 */
export function subscribe<T>(destination: string, onMessage: (payload: T) => void): () => void {
  const token = sessionStore.getState().accessToken

  const client = new Client({
    webSocketFactory: () => new SockJS(`${baseURL}/ws`),
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    reconnectDelay: 4000,
    onConnect: () => {
      client.subscribe(destination, (message: IMessage) => {
        try {
          onMessage(JSON.parse(message.body) as T)
        } catch {
          /* ignore malformed frames */
        }
      })
    },
  })

  client.activate()

  return () => {
    void client.deactivate()
  }
}

/** Convenience: the per-tenant Strange Loop topic. */
export function loopTopic(tenantId: number): string {
  return `/topic/tenant.${tenantId}.loop`
}
