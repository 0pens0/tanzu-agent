import { Injectable, signal } from '@angular/core';

export interface AuthStatus {
  authenticated: boolean;
  userId: string;
  username: string;
  email: string;
  displayName: string;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private _authStatus = signal<AuthStatus | null>(null);
  readonly authStatus = this._authStatus.asReadonly();

  async checkAuthStatus(): Promise<AuthStatus> {
    try {
      const response = await fetch('/auth/status', { credentials: 'same-origin' });
      if (response.status === 401 || response.status === 403) {
        window.location.href = '/oauth2/authorization/sso';
        return { authenticated: false, userId: '', username: '', email: '', displayName: '' };
      }
      if (!response.ok) {
        return { authenticated: false, userId: '', username: '', email: '', displayName: '' };
      }
      const status: AuthStatus = await response.json();
      this._authStatus.set(status);
      return status;
    } catch {
      return { authenticated: false, userId: '', username: '', email: '', displayName: '' };
    }
  }

  logout(): void {
    window.location.href = '/logout';
  }
}
