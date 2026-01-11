import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  streaming?: boolean;
}

export interface SessionInfo {
  sessionId: string;
  createdAt: Date;
  provider?: string;
  model?: string;
}

export interface HealthInfo {
  available: boolean;
  version: string;
  provider: string;
  model: string;
  message?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private readonly apiUrl = '/api/chat';
  private currentSession = signal<SessionInfo | null>(null);

  /**
   * Get the current active session
   */
  getCurrentSession(): SessionInfo | null {
    return this.currentSession();
  }

  /**
   * Create a new conversation session
   */
  async createSession(
    provider?: string, 
    model?: string,
    timeoutMinutes?: number
  ): Promise<string> {
    const body: Record<string, unknown> = {};
    if (provider) body['provider'] = provider;
    if (model) body['model'] = model;
    if (timeoutMinutes) body['sessionInactivityTimeoutMinutes'] = timeoutMinutes;

    const response = await fetch(`${this.apiUrl}/sessions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: Object.keys(body).length > 0 ? JSON.stringify(body) : undefined
    });

    if (!response.ok) {
      throw new Error(`Failed to create session: ${response.status}`);
    }

    const result = await response.json();
    if (!result.success || !result.sessionId) {
      throw new Error(result.message || 'Failed to create session');
    }

    this.currentSession.set({
      sessionId: result.sessionId,
      createdAt: new Date(),
      provider,
      model
    });

    console.log('Created new Goose session:', result.sessionId);
    return result.sessionId;
  }

  /**
   * Send a message to the current session with SSE streaming.
   * 
   * Uses the native browser EventSource API for better handling of proxy buffering
   * and chunked encoding (especially important for Cloud Foundry's Go Router).
   * 
   * Uses token-level streaming from Goose CLI's --output-format stream-json.
   * Each emitted value is a single token as it arrives from the LLM.
   * 
   * SSE Events handled:
   * - `token`: Individual tokens (emitted to observer)
   * - `complete`: Stream completion with token count
   * - `status`: Processing status messages (logged)
   * - `error`: Error events
   */
  sendMessage(message: string, sessionId: string): Observable<string> {
    return new Observable(observer => {
      // Use GET endpoint with EventSource for better streaming support
      // EventSource handles proxy buffering and chunked encoding better than fetch
      const encodedMessage = encodeURIComponent(message);
      const url = `${this.apiUrl}/sessions/${sessionId}/stream?message=${encodedMessage}`;
      
      console.log('[SSE] Connecting to:', url);
      const eventSource = new EventSource(url);
      
      eventSource.onopen = () => {
        console.log('[SSE] Connection opened');
      };

      // Handle token events - individual tokens as they arrive
      eventSource.addEventListener('token', (event: MessageEvent) => {
        const data = event.data;
        if (data && data.length > 0) {
          // Log short tokens for debugging
          if (data.length <= 20) {
            console.debug(`[SSE token] "${data}"`);
          }
          observer.next(data);
        }
      });

      // Handle status events
      eventSource.addEventListener('status', (event: MessageEvent) => {
        console.log('[SSE status]', event.data);
      });

      // Handle completion event
      eventSource.addEventListener('complete', (event: MessageEvent) => {
        console.log('[SSE complete] Total tokens:', event.data);
        eventSource.close();
        observer.complete();
      });

      // Handle error events from the server
      eventSource.addEventListener('error', (event: MessageEvent) => {
        console.error('[SSE error]', event.data);
        eventSource.close();
        observer.error(new Error(event.data || 'Stream error'));
      });

      // Handle connection errors
      eventSource.onerror = (event: Event) => {
        // Check if the connection was closed normally (after 'complete' event)
        if (eventSource.readyState === EventSource.CLOSED) {
          console.log('[SSE] Connection closed');
          observer.complete();
        } else {
          console.error('[SSE] Connection error:', event);
          eventSource.close();
          observer.error(new Error('Connection to server failed'));
        }
      };

      // Cleanup on unsubscribe
      return () => {
        console.log('[SSE] Closing connection');
        eventSource.close();
      };
    });
  }

  /**
   * Close the current conversation session
   */
  async closeSession(sessionId: string): Promise<void> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}`, {
        method: 'DELETE'
      });

      if (!response.ok) {
        console.warn(`Failed to close session ${sessionId}: ${response.status}`);
      }

      const currentSessionInfo = this.currentSession();
      if (currentSessionInfo && currentSessionInfo.sessionId === sessionId) {
        this.currentSession.set(null);
      }

      console.log('Closed Goose session:', sessionId);
    } catch (error) {
      console.error('Error closing session:', error);
      throw error;
    }
  }

  /**
   * Check if a session is active
   */
  async checkSessionStatus(sessionId: string): Promise<boolean> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}/status`);
      if (!response.ok) {
        return false;
      }
      const result = await response.json();
      return result.active;
    } catch (error) {
      console.error('Error checking session status:', error);
      return false;
    }
  }

  /**
   * Check Goose health status
   */
  checkHealth(): Promise<HealthInfo> {
    return fetch(`${this.apiUrl}/health`)
      .then(response => response.json())
      .catch(error => {
        console.error('Health check failed:', error);
        return { 
          available: false, 
          version: 'unknown',
          provider: 'unknown',
          model: 'unknown',
          message: 'Health check endpoint not reachable' 
        };
      });
  }
}

